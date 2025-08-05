#!/bin/bash

# Bisect Results Viewer
# Script to view and manage local bisect results

RESULTS_DIR="/tmp/bisect_work/results"

echo "CTP Bisect Results Viewer"
echo "========================"
echo "Results directory: $RESULTS_DIR"
echo

# Check if results directory exists
if [ ! -d "$RESULTS_DIR" ]; then
    echo "Results directory does not exist yet."
    echo "Results will be saved here after bisect tasks complete."
    exit 1
fi

# List available result files
echo "Available Results:"
echo "-----------------"
if ls "$RESULTS_DIR"/*.json >/dev/null 2>&1; then
    for file in "$RESULTS_DIR"/bisect_result_*.json; do
        if [ -f "$file" ]; then
            filename=$(basename "$file")
            timestamp=$(echo "$filename" | sed 's/bisect_result_\([0-9_]*\)_.*\.json/\1/' | sed 's/_/ /')
            task_id=$(echo "$filename" | sed 's/bisect_result_[0-9_]*_\(.*\)\.json/\1/')
            size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)
            echo "  $filename (Task: $task_id, Size: ${size} bytes)"
        fi
    done
    
    # Show latest result if it exists
    if [ -f "$RESULTS_DIR/latest_result.json" ]; then
        echo
        echo "Latest Result Summary:"
        echo "---------------------"
        if command -v jq >/dev/null 2>&1; then
            echo "Suspected commit range: $(jq -r '.suspectedStartCommit + "..." + .suspectedEndCommit' "$RESULTS_DIR/latest_result.json")"
            echo "Worker IP: $(jq -r '.workerIp' "$RESULTS_DIR/latest_result.json")"
            echo "Generated at: $(jq -r '.generatedAt' "$RESULTS_DIR/latest_result.json")"
            echo "Number of tests: $(jq -r '.tests | length' "$RESULTS_DIR/latest_result.json")"
            echo
            echo "Test Results:"
            jq -r '.tests[] | "  \(.testName): \(.status) (First bad: \(.firstBadCommit // "N/A"), Last good: \(.lastGoodCommit // "N/A"))"' "$RESULTS_DIR/latest_result.json"
        else
            echo "Install 'jq' for better JSON parsing, or view raw file:"
            echo "  cat $RESULTS_DIR/latest_result.json"
        fi
    fi
else
    echo "No result files found."
    echo "Results will appear here after bisect tasks complete with the updated code."
fi

echo
echo "Commands:"
echo "  View specific result: cat $RESULTS_DIR/bisect_result_YYYYMMDD_HHMMSS_TASKID.json"
echo "  View latest result:   cat $RESULTS_DIR/latest_result.json"
echo "  Monitor new results:  watch -n 5 ls -la $RESULTS_DIR/"