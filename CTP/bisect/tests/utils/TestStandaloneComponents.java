/**
 * Test utility for standalone bisect components
 * This tests the standalone implementation without requiring Docker execution
 */

import java.io.*;
import java.util.logging.*;
import com.navercorp.cubridqa.bisect.*;

public class TestStandaloneComponents {
    private static final Logger logger = Logger.getLogger(TestStandaloneComponents.class.getName());
    
    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("Testing Standalone Bisect Components");
        System.out.println("==============================================");
        
        boolean allTestsPassed = true;
        
        // Test 1: BisectConfig can load standalone configuration
        try {
            System.out.println("\n1. Testing BisectConfig with standalone configuration...");
            BisectConfig config = new BisectConfig("../../conf/bisect_standalone.conf");
            
            // Verify standalone mode is enabled
            if (!config.isStandaloneMode()) {
                System.out.println("ERROR: Standalone mode not enabled in configuration");
                allTestsPassed = false;
            } else {
                System.out.println("✓ Standalone mode is enabled");
            }
            
            // Verify required configuration values
            if (config.getListenPort() != 8091) {
                System.out.println("ERROR: Expected port 8091, got " + config.getListenPort());
                allTestsPassed = false;
            } else {
                System.out.println("✓ Listen port configured correctly: " + config.getListenPort());
            }
            
            System.out.println("✓ BisectConfig loaded successfully");
            
        } catch (Exception e) {
            System.out.println("ERROR: Failed to load configuration: " + e.getMessage());
            allTestsPassed = false;
        }
        
        // Test 2: StandaloneDockerManager can be created (but not initialized without Docker)
        try {
            System.out.println("\n2. Testing StandaloneDockerManager creation...");
            BisectConfig config = new BisectConfig("../../conf/bisect_standalone.conf");
            StandaloneDockerManager manager = new StandaloneDockerManager(config);
            
            // Check if Docker is available
            if (!manager.isReady()) {
                System.out.println("⚠ Docker not available - manager not ready (expected in this environment)");
            } else {
                System.out.println("✓ Docker is available and manager is ready");
            }
            
            System.out.println("✓ StandaloneDockerManager created successfully");
            
        } catch (Exception e) {
            System.out.println("ERROR: Failed to create StandaloneDockerManager: " + e.getMessage());
            allTestsPassed = false;
        }
        
        // Test 3: StandaloneBisectExecutor can be created
        try {
            System.out.println("\n3. Testing StandaloneBisectExecutor creation...");
            BisectConfig config = new BisectConfig("../../conf/bisect_standalone.conf");
            StandaloneBisectExecutor executor = new StandaloneBisectExecutor(config);
            
            System.out.println("✓ StandaloneBisectExecutor created successfully");
            
            // Shutdown executor
            executor.shutdown();
            System.out.println("✓ StandaloneBisectExecutor shutdown successfully");
            
        } catch (Exception e) {
            System.out.println("ERROR: Failed to create StandaloneBisectExecutor: " + e.getMessage());
            allTestsPassed = false;
        }
        
        // Test 4: StandaloneBisectService can be created (but not started without proper setup)
        try {
            System.out.println("\n4. Testing StandaloneBisectService creation...");
            BisectConfig config = new BisectConfig("../../conf/bisect_standalone.conf");
            StandaloneBisectService service = new StandaloneBisectService(config);
            
            System.out.println("✓ StandaloneBisectService created successfully");
            
            // Note: We don't start the service in tests to avoid port conflicts
            
        } catch (Exception e) {
            System.out.println("ERROR: Failed to create StandaloneBisectService: " + e.getMessage());
            allTestsPassed = false;
        }
        
        // Test 5: Validate standalone configuration has all required fields
        try {
            System.out.println("\n5. Testing configuration completeness...");
            BisectConfig config = new BisectConfig("../../conf/bisect_standalone.conf");
            
            // Check required paths
            String cubridSrc = config.getCubridSrcDir();
            String shellTc = config.getShellTcDir();
            String workDir = config.getWorkDir();
            
            if (cubridSrc == null || cubridSrc.trim().isEmpty()) {
                System.out.println("ERROR: CUBRID source directory not configured");
                allTestsPassed = false;
            } else {
                System.out.println("✓ CUBRID source directory: " + cubridSrc);
            }
            
            if (shellTc == null || shellTc.trim().isEmpty()) {
                System.out.println("ERROR: Shell test case directory not configured");
                allTestsPassed = false;
            } else {
                System.out.println("✓ Shell test case directory: " + shellTc);
            }
            
            if (workDir == null || workDir.trim().isEmpty()) {
                System.out.println("ERROR: Work directory not configured");
                allTestsPassed = false;
            } else {
                System.out.println("✓ Work directory: " + workDir);
            }
            
            // Check build configuration
            String buildArg = config.getBuildArg();
            String buildDir = config.getBuildDir();
            
            if (buildArg == null || buildArg.trim().isEmpty()) {
                System.out.println("ERROR: Build arguments not configured");
                allTestsPassed = false;
            } else {
                System.out.println("✓ Build arguments: " + buildArg);
            }
            
            if (buildDir == null || buildDir.trim().isEmpty()) {
                System.out.println("ERROR: Build directory not configured");
                allTestsPassed = false;
            } else {
                System.out.println("✓ Build directory: " + buildDir);
            }
            
        } catch (Exception e) {
            System.out.println("ERROR: Configuration validation failed: " + e.getMessage());
            allTestsPassed = false;
        }
        
        // Final result
        System.out.println("\n==============================================");
        if (allTestsPassed) {
            System.out.println("✓ ALL TESTS PASSED");
            System.out.println("Standalone components are properly implemented and configured");
            System.exit(0);
        } else {
            System.out.println("✗ SOME TESTS FAILED");
            System.out.println("There are issues with the standalone implementation");
            System.exit(1);
        }
    }
}