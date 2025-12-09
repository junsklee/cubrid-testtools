#!/usr/bin/env python3
"""
Script to extract test results from builder.log and resend the callback
"""
import argparse
from collections import defaultdict, deque
import json
import os
import re
import sys
import urllib.request
import urllib.error
from datetime import datetime

def load_test_paths_from_request_json(log_file):
    """Load test paths from request.json as a fallback"""
    test_path_map = defaultdict(deque)
    log_dir = os.path.dirname(log_file)
    request_json_path = os.path.join(log_dir, "request.json")
    
    if os.path.exists(request_json_path):
        try:
            with open(request_json_path, 'r', encoding='utf-8') as f:
                request_data = json.load(f)
                tests = request_data.get("tests", [])
                for test_path in tests:
                    # Extract test name from full path
                    test_name_match = re.search(r'/([^/]+)\.sh$', test_path)
                    if test_name_match:
                        test_name = test_name_match.group(1)
                        test_path_map[test_name].append(test_path)
        except Exception as e:
            print(f"Warning: Could not load test paths from request.json: {e}", file=sys.stderr)
    
    return test_path_map

def build_test_path_mapping(log_file):
    """Build a mapping from test name to a queue of full test paths from the log"""
    test_path_map = defaultdict(deque)
    
    # Pattern 1: Match "Assignment{test=full/path/to/test.sh, ...}" from Smart Scheduling
    assignment_pattern = r"Assignment\{test=([^,]+\.sh)"
    assignment_count = 0
    
    with open(log_file, 'r', encoding='utf-8') as f:
        for line in f:
            # Look for Assignment lines (most reliable source of full paths)
            match = re.search(assignment_pattern, line)
            if match:
                full_path = match.group(1)
                # Extract test name from full path (e.g., "bug_bts_10863" from "shell/_06_issues/_13_1h/bug_bts_10863/cases/bug_bts_10863.sh")
                test_name_match = re.search(r'/([^/]+)\.sh$', full_path)
                if test_name_match:
                    test_name = test_name_match.group(1)
                    test_path_map[test_name].append(full_path)
                    assignment_count += 1
    
    # Always load request.json to fill in any gaps or duplicate test names
    request_map = load_test_paths_from_request_json(log_file)
    added_count = 0
    if request_map:
        for test_name, paths in request_map.items():
            existing_paths = test_path_map[test_name]
            existing_len = len(existing_paths)
            # If request.json lists the same test name multiple times (in different directories),
            # append the missing occurrences so we can map every tester response.
            for path in list(paths)[existing_len:]:
                existing_paths.append(path)
                added_count += 1
    
    total_paths = sum(len(paths) for paths in test_path_map.values())
    if assignment_count == 0 and total_paths > 0:
        print("No test paths found in log, using request.json entries...")
    print(f"Built test path mapping with {total_paths} test entries across {len(test_path_map)} unique test names")
    if added_count > 0 and assignment_count > 0:
        print(f"Added {added_count} missing test paths from request.json to cover duplicates or missing assignments")
    
    return test_path_map

def extract_results_from_log(log_file, expected_commit=None):
    """Extract test results from the builder.log file, filtering by expected commit"""
    results = []
    
    # First, build a mapping from test name to full path
    test_path_map = build_test_path_mapping(log_file)
    total_test_paths = sum(len(paths) for paths in test_path_map.values())
    print(f"Final test path mapping has {total_test_paths} entries across {len(test_path_map)} unique test names")
    
    missing_path_count = 0
    
    with open(log_file, 'r', encoding='utf-8') as f:
        for line in f:
            # Look for the pattern anywhere in the line
            if 'Tester response payload' in line:
                # Extract the part after "Tester response payload for 'test' on host:port: "
                # The pattern needs to handle IP:port format (e.g., 192.168.1.5:8090: {...})
                match = re.search(r"Tester response payload for '([^']+)' on [^:]+:[0-9]+: (.+)", line)
                if match:
                    test_name = match.group(1)
                    payload_json = match.group(2).strip()  # Remove trailing whitespace
                    
                    try:
                        payload = json.loads(payload_json)
                        
                        # Get commit from payload
                        commit = payload.get("commit", "")
                        
                        # Filter: only include results with the expected commit
                        # Skip results with empty commits (these are error cases)
                        if expected_commit:
                            if not commit or commit != expected_commit:
                                continue
                        elif not commit:
                            # If no expected commit specified, skip empty commits
                            continue
                        
                        # Get full test path from mapping
                        test_queue = test_path_map.get(test_name)
                        test_path = None
                        if test_queue and len(test_queue) > 0:
                            test_path = test_queue.popleft()
                        else:
                            # Last resort: use test name (shouldn't happen, but be safe)
                            missing_path_count += 1
                            print(f"Warning: No full path found for test '{test_name}', using test name as fallback", file=sys.stderr)
                            test_path = test_name
                        
                        # Transform to BuilderTask result format (matches Java code structure)
                        result = {
                            "commit": commit,
                            "test": test_path,  # Use full path as in Java code
                            "status": payload.get("status", "unknown"),
                            "message": payload.get("message", "")
                        }
                        
                        # Add optional fields if present (matches Java code)
                        if "attempts" in payload:
                            result["attempts"] = payload["attempts"]
                        if "flaky" in payload:
                            result["flaky"] = payload["flaky"]
                        
                        results.append(result)
                    except json.JSONDecodeError as e:
                        print(f"Warning: Failed to parse JSON for test {test_name}: {e}", file=sys.stderr)
                        continue
    
    leftover_paths = sum(len(paths) for paths in test_path_map.values())
    if leftover_paths > 0:
        print(f"Warning: {leftover_paths} test path mappings were not matched to a response line", file=sys.stderr)
    if missing_path_count > 0:
        print(f"Warning: {missing_path_count} tester responses did not have a mapped full path", file=sys.stderr)
    
    return results

def extract_metadata_from_log(log_file):
    """Extract metadata like baselineCommit, taskId, execution time, and expected commit from log"""
    metadata = {
        "baselineCommit": None,
        "taskId": None,
        "executionTimeSeconds": None,
        "expectedCommit": None
    }
    
    with open(log_file, 'r', encoding='utf-8') as f:
        content = f.read()
        
        # Extract baseline commit
        baseline_match = re.search(r'Using baseline \(parent of earliest commit\): ([a-f0-9]+)', content)
        if baseline_match:
            metadata["baselineCommit"] = baseline_match.group(1)
        
        # Extract expected commit (the commit that was actually built)
        commit_match = re.search(r'Using cached build from disk for commit ([a-f0-9]+)', content)
        if commit_match:
            metadata["expectedCommit"] = commit_match.group(1)
        else:
            # Alternative: look for "Building X commits" and extract from test entries
            commit_match = re.search(r"Test \d+/\d+: .* \(commit ([a-f0-9]+)\)", content)
            if commit_match:
                # Get the short commit, need to find the full one
                short_commit = commit_match.group(1)
                # Look for full commit in test response payloads
                full_commit_match = re.search(r'"commit":"([a-f0-9]+)"', content)
                if full_commit_match:
                    full_commit = full_commit_match.group(1)
                    if full_commit.startswith(short_commit):
                        metadata["expectedCommit"] = full_commit
        
        # Extract taskId (requestId) from log prefix
        task_match = re.search(r'\[([^\]]+)\]', content)
        if task_match:
            metadata["taskId"] = task_match.group(1)
        
        # Extract execution time
        # Look for "Builder task req_xxx completed in XXX seconds"
        time_match = re.search(r'Builder task [^\s]+ completed in (\d+) seconds', content)
        if time_match:
            metadata["executionTimeSeconds"] = int(time_match.group(1))
        else:
            # Alternative: look for "req_20251105_220836_e33e] 2025-11-06 07:26:51" and "Starting builder task"
            # Calculate from start and end times
            start_match = re.search(r'Starting builder task: ([^\s]+)', content)
            end_match = re.search(r'Builder task ([^\s]+) completed in (\d+) seconds', content)
            if end_match:
                metadata["executionTimeSeconds"] = int(end_match.group(2))
    
    return metadata

def format_execution_time(seconds):
    """Format execution time in user-friendly format"""
    if seconds < 60:
        return f"{seconds}s"
    elif seconds < 3600:
        minutes = seconds // 60
        secs = seconds % 60
        return f"{minutes}m {secs}s"
    else:
        hours = seconds // 3600
        minutes = (seconds % 3600) // 60
        return f"{hours}h {minutes}m"

def send_callback(callback_url, payload):
    """Send callback to the specified URL"""
    try:
        # Encode the JSON payload
        data = json.dumps(payload).encode('utf-8')
        
        # Create the request
        req = urllib.request.Request(callback_url, data=data, method='POST')
        req.add_header('Content-Type', 'application/json')
        
        # Send the request
        print(f"Sending callback to {callback_url}...")
        with urllib.request.urlopen(req) as response:
            response_code = response.getcode()
            response_body = response.read().decode('utf-8')
            print(f"Callback sent successfully! Response code: {response_code}")
            if response_body:
                print(f"Response body: {response_body[:500]}")  # Print first 500 chars
            return True
    except urllib.error.URLError as e:
        print(f"Error sending callback: {e}", file=sys.stderr)
        return False
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        return False

def main():
    parser = argparse.ArgumentParser(
        description="Extract test results from builder.log and resend callback to report server"
    )
    parser.add_argument(
        "log_file",
        nargs="?",
        default="/home/qahome/cubrid-testtools/CTP/builder_tester/log/requests/req_20251105_220836_e33e/builder.log",
        help="Path to builder.log file (default: example request log)"
    )
    parser.add_argument(
        "--callback-url",
        default="http://localhost:8091/callback",
        help="Callback URL (default: http://localhost:8091/callback)"
    )
    args = parser.parse_args()
    
    log_file = args.log_file
    callback_url = args.callback_url
    
    # If log_file is relative, try to resolve it relative to builder_tester directory
    if not os.path.isabs(log_file):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        builder_tester_dir = os.path.dirname(script_dir)
        log_file = os.path.join(builder_tester_dir, log_file)
    
    if not os.path.exists(log_file):
        print(f"Error: Log file not found: {log_file}", file=sys.stderr)
        sys.exit(1)
    
    print(f"Reading log file: {log_file}")
    print("Extracting metadata from log file...")
    metadata = extract_metadata_from_log(log_file)
    print(f"Metadata: {metadata}")
    
    print("\nExtracting test results from log file...")
    # Filter results to only include the expected commit
    expected_commit = metadata.get("expectedCommit")
    if expected_commit:
        print(f"Filtering results to only include commit: {expected_commit}")
    results = extract_results_from_log(log_file, expected_commit=expected_commit)
    print(f"Found {len(results)} test results (filtered by expected commit)")
    
    if not metadata["taskId"]:
        # Try to extract from log file path
        log_dir = os.path.dirname(log_file)
        req_id = os.path.basename(log_dir)
        if req_id.startswith("req_"):
            metadata["taskId"] = req_id
        else:
            metadata["taskId"] = "unknown"
    
    if not metadata["baselineCommit"]:
        print("Warning: Could not extract baselineCommit from log", file=sys.stderr)
        metadata["baselineCommit"] = "e00307bc1149f143b7648620193d34d1c0bee86b"
    
    if not metadata["executionTimeSeconds"]:
        print("Warning: Could not extract execution time from log, using 33491 seconds", file=sys.stderr)
        metadata["executionTimeSeconds"] = 33491
    
    # Build the callback payload
    payload = {
        "requestId": metadata["taskId"],
        "taskId": metadata["taskId"],
        "results": results,
        "baselineCommit": metadata["baselineCommit"],
        "executionTime": format_execution_time(metadata["executionTimeSeconds"]),
        "timestamp": int(datetime.now().timestamp() * 1000)
    }
    
    print(f"\nCallback payload summary:")
    print(f"  requestId: {payload['requestId']}")
    print(f"  taskId: {payload['taskId']}")
    print(f"  results count: {len(payload['results'])}")
    print(f"  baselineCommit: {payload['baselineCommit']}")
    print(f"  executionTime: {payload['executionTime']}")
    print(f"  timestamp: {payload['timestamp']}")
    
    # Send the callback
    success = send_callback(callback_url, payload)
    
    if success:
        print("\nCallback sent successfully!")
        sys.exit(0)
    else:
        print("\nFailed to send callback", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
