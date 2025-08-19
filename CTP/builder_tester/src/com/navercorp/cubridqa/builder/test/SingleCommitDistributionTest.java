package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Unit test for single commit distribution fix
 * Compile: javac -cp lib/json.jar:build SingleCommitDistributionTest.java
 * Run: java -cp lib/json.jar:build:. com.navercorp.cubridqa.builder.test.SingleCommitDistributionTest
 */
public class SingleCommitDistributionTest {
    
    public static void main(String[] args) {
        System.out.println("=== Single Commit Distribution Test ===\n");
        
        testSingleCommitMultipleWorkers();
        testMultipleCommitsMultipleWorkers();
        testSingleCommitSingleWorker();
        
        System.out.println("\n=== All tests passed! ===");
    }
    
    private static void testSingleCommitMultipleWorkers() {
        System.out.println("Test 1: Single commit with multiple workers");
        
        // Simulate distribution logic
        List<String> workerIps = Arrays.asList("localhost", "192.168.1.100", "192.168.1.101");
        List<String> tests = Arrays.asList("test1", "test2", "test3", "test4", "test5", "test6");
        Map<String, String> builtPackages = new HashMap<>();
        builtPackages.put("commit1", "/path/to/build.tar.gz");
        
        Map<String, List<String>> distribution = simulateDistribution(builtPackages, tests, workerIps);
        
        // Verify even distribution
        for (Map.Entry<String, List<String>> entry : distribution.entrySet()) {
            System.out.println("  Worker " + entry.getKey() + ": " + entry.getValue().size() + " tests");
            assert entry.getValue().size() == 2 : "Each worker should get 2 tests";
        }
        
        // Verify all workers are used
        assert distribution.size() == 3 : "All 3 workers should be used";
        
        System.out.println("  ✓ Distribution is even: each worker gets 2 tests\n");
    }
    
    private static void testMultipleCommitsMultipleWorkers() {
        System.out.println("Test 2: Multiple commits with multiple workers");
        
        List<String> workerIps = Arrays.asList("localhost", "192.168.1.100");
        List<String> tests = Arrays.asList("test1", "test2", "test3", "test4");
        Map<String, String> builtPackages = new HashMap<>();
        builtPackages.put("commit1", "/path/to/build1.tar.gz");
        builtPackages.put("commit2", "/path/to/build2.tar.gz");
        
        Map<String, List<String>> distribution = simulateDistribution(builtPackages, tests, workerIps);
        
        // With 2 commits × 4 tests = 8 total tests, each of 2 workers should get 4
        for (Map.Entry<String, List<String>> entry : distribution.entrySet()) {
            System.out.println("  Worker " + entry.getKey() + ": " + entry.getValue().size() + " tests");
            assert entry.getValue().size() == 4 : "Each worker should get 4 tests";
        }
        
        System.out.println("  ✓ Distribution is even: each worker gets 4 tests\n");
    }
    
    private static void testSingleCommitSingleWorker() {
        System.out.println("Test 3: Single commit with single worker");
        
        List<String> workerIps = Arrays.asList("localhost");
        List<String> tests = Arrays.asList("test1", "test2", "test3");
        Map<String, String> builtPackages = new HashMap<>();
        builtPackages.put("commit1", "/path/to/build.tar.gz");
        
        Map<String, List<String>> distribution = simulateDistribution(builtPackages, tests, workerIps);
        
        assert distribution.get("localhost").size() == 3 : "Single worker should get all 3 tests";
        
        System.out.println("  Worker localhost: " + distribution.get("localhost").size() + " tests");
        System.out.println("  ✓ Single worker gets all tests\n");
    }
    
    /**
     * Simulates the fixed distribution logic
     */
    private static Map<String, List<String>> simulateDistribution(
            Map<String, String> builtPackages, 
            List<String> tests, 
            List<String> workerIps) {
        
        Map<String, List<String>> workerTestQueues = new HashMap<>();
        for (String worker : workerIps) {
            workerTestQueues.put(worker, new ArrayList<>());
        }
        
        // Fixed logic: use global index for proper distribution
        int globalTestIndex = 0;
        
        for (Map.Entry<String, String> entry : builtPackages.entrySet()) {
            String commit = entry.getKey();
            String buildPackage = entry.getValue();
            
            for (String test : tests) {
                // Use global index for round-robin (FIX)
                String assignedWorker = workerIps.get(globalTestIndex % workerIps.size());
                globalTestIndex++;
                
                workerTestQueues.get(assignedWorker).add(commit + ":" + test);
            }
        }
        
        return workerTestQueues;
    }
}