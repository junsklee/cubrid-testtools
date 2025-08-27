package com.navercorp.cubridqa.builder.exec;

public class EnvScriptFactory {
    
    public static String createDirectWrapperScript(String testDir, String testScript, String testName, String ctpHome) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n\n");
        
        script.append("# Set up environment\n");
        script.append("export CTP_HOME=\"").append(ctpHome).append("\"\n");
        script.append("export PATH=\"$CTP_HOME/shell/init_path:$PATH\"\n");
        script.append("export PATH=\"$HOME/CUBRID/bin:$PATH\"\n");
        script.append("export LD_LIBRARY_PATH=\"$HOME/CUBRID/lib:$LD_LIBRARY_PATH\"\n\n");
        
        script.append("# Change to test directory\n");
        script.append("cd \"").append(testDir).append("\"\n\n");
        
        script.append("# Source shell test framework if available\n");
        script.append("if [ -f \"$CTP_HOME/shell/init_path/init.sh\" ]; then\n");
        script.append("    source \"$CTP_HOME/shell/init_path/init.sh\"\n");
        script.append("fi\n\n");
        
        script.append("# Execute test\n");
        script.append("bash \"").append(testScript).append("\"\n");
        script.append("TEST_EXIT_CODE=$?\n\n");
        
        script.append("exit $TEST_EXIT_CODE\n");
        
        return script.toString();
    }
    
    public static String createDockerScript(String testScript, String testName, String expectedBuildVersion, String relativeTestDir) {
         StringBuilder script = new StringBuilder();
         script.append("#!/bin/bash\n");
         script.append("set -e\n");
         script.append("set -x\n\n");
         
         script.append("# Extract CUBRID build\n");
         script.append("echo \"Extracting CUBRID build...\"\n");
         script.append("mkdir -p /tmp/cubrid_install\n");
         script.append("cd /tmp/cubrid_install\n");
         script.append("tar -xzf /workspace/build.tar.gz\n");
         script.append("# Prefer packaged install layout\n");
         script.append("if [ -d /tmp/cubrid_install/_install/CUBRID ]; then\n");
         script.append("  CUBRID_ROOT=/tmp/cubrid_install/_install/CUBRID\n");
         script.append("else\n");
         script.append("  CUBRID_ROOT=$(find /tmp/cubrid_install -name \"bin\" -type d | head -1 | xargs dirname)\n");
         script.append("fi\n");
         script.append("if [ -z \"$CUBRID_ROOT\" ]; then\n");
         script.append("    echo \"ERROR: Could not find CUBRID installation\"\n");
         script.append("    exit 1\n");
         script.append("fi\n\n");
         
         script.append("# Run setup.sh to configure CUBRID if available (non-interactive)\n");
         script.append("if [ -f \"$CUBRID_ROOT/share/scripts/setup.sh\" ]; then\n");
         script.append("    echo \"Running setup.sh...\"\n");
         script.append("    cd \"$CUBRID_ROOT\"\n");
         script.append("    yes | sh share/scripts/setup.sh \"$CUBRID_ROOT\" || true\n");
         script.append("    cd -\n");
         script.append("elif [ -f \"$CUBRID_ROOT/setup.sh\" ]; then\n");
         script.append("    echo \"Running setup.sh...\"\n");
         script.append("    cd \"$CUBRID_ROOT\"\n");
         script.append("    yes | sh setup.sh \"$CUBRID_ROOT\" || true\n");
         script.append("    cd -\n");
         script.append("fi\n\n");
 
         script.append("# Set up CUBRID environment\n");
         script.append("# Source default env if created by setup\n");
         script.append("if [ -f /root/.cubrid.sh ]; then\n");
         script.append("  . /root/.cubrid.sh\n");
         script.append("fi\n");
         script.append("export CUBRID=\"$CUBRID_ROOT\"\n");
         script.append("export PATH=\"$CUBRID_ROOT/bin:/home/cubrid-testtools/CTP/shell/init_path:$PATH\"\n");
         script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$CUBRID_ROOT/cci/lib:$CUBRID_ROOT/lib64:$LD_LIBRARY_PATH\"\n");
         script.append("export CUBRID_LANG=\"en_US\"\n");
         script.append("export CUBRID_CHARSET=\"en_US\"\n");
         script.append("export CTP_HOME=\"/home/cubrid-testtools/CTP\"\n");
         script.append("export init_path=\"/home/cubrid-testtools/CTP/shell/init_path\"\n\n");

         script.append("# Emit debug env snapshot for docker exec sessions\n");
         script.append("cat > /workspace/debug_env.sh <<'EOS'\n");
         script.append("export CUBRID=\"$CUBRID_ROOT\"\n");
         script.append("export CUBRID_DATABASES=\"$CUBRID_ROOT/databases\"\n");
         script.append("export PATH=\"$CUBRID_ROOT/bin:$PATH\"\n");
         script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$CUBRID_ROOT/cci/lib:$CUBRID_ROOT/lib64:$LD_LIBRARY_PATH\"\n");
         script.append("export CTP_HOME=\"/home/cubrid-testtools/CTP\"\n");
         script.append("export init_path=\"/home/cubrid-testtools/CTP/shell/init_path\"\n");
         script.append("EOS\n");
         script.append("chmod +x /workspace/debug_env.sh\n\n");
         
         script.append("# Verify installation\n");
         script.append("echo \"Verifying CUBRID installation...\"\n");
         script.append("if ! cubrid_rel; then\n");
         script.append("    echo \"ERROR: CUBRID verification failed\"\n");
         script.append("    exit 1\n");
         script.append("fi\n\n");
         
         script.append("mkdir -p \"$CUBRID_DATABASES\"\n");
         script.append("# Ensure clean server log directory to avoid leftover state\n");
         script.append("mkdir -p \"$CUBRID/log/server\"\n");
         script.append("rm -f \"$CUBRID/log/server/*\" || true\n");
         script.append("# Reset databases registry so createdb uses this workspace\n");
         script.append(": > \"$CUBRID_DATABASES/databases.txt\"\n\n");
 
         // If expected build version provided, verify
         script.append("# Verify expected build version if provided\n");
         script.append("if [ -n \"");
         script.append(expectedBuildVersion != null ? expectedBuildVersion : "");
         script.append("\" ]; then\n");
         script.append("    INSTALLED_VER=$(cubrid_rel 2>/dev/null | head -1)\n");
         script.append("    echo \"Installed version: $INSTALLED_VER\"\n");
         if (expectedBuildVersion != null) {
             script.append("    if [[ \"$INSTALLED_VER\" != *\"");
             script.append(expectedBuildVersion);
             script.append("\"* ]]; then\n");
             script.append("        echo \"WARNING: Expected build version ");
             script.append(expectedBuildVersion);
             script.append(" not found in installed version\"\n");
             script.append("    fi\n");
         }
         script.append("fi\n\n");
         
         script.append("# Run test\n");
         script.append("cd /workspace/testcases\n");
         script.append("bash ").append(testScript).append("\n");
         script.append("TEST_EXIT=$?\n\n");
         
         // Determine result base name from test script (strip .sh)
         String resultBase = testScript.endsWith(".sh") ? 
             testScript.substring(0, testScript.length() - 3) :
             (testScript.contains(".") ? testScript.substring(0, testScript.lastIndexOf('.')) : testScript);
         script.append("# Copy result file if generated by test\n");
         script.append("if [ -f \"" + resultBase + ".result\" ]; then\n");
         script.append("    cp \"" + resultBase + ".result\" /workspace/\n");
         script.append("fi\n\n");
         
         script.append("exit $TEST_EXIT\n");
         
         return script.toString();
    }
    
    public static String createDockerOptimizedScript(String testScript, String testName, String expectedBuildVersion) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n");
        script.append("set -x\n\n");
        
        script.append("# CUBRID is pre-installed in /opt/cubrid\n");
        script.append("export CUBRID=/opt/cubrid\n");
        script.append("export CUBRID_DATABASES=/opt/cubrid/databases\n");
        script.append("export PATH=/opt/cubrid/bin:/home/cubrid-testtools/CTP/shell/init_path:$PATH\n");
        script.append("export LD_LIBRARY_PATH=/opt/cubrid/lib:/opt/cubrid/cci/lib:$LD_LIBRARY_PATH\n");
        script.append("export CUBRID_LANG=en_US\n");
        script.append("export CUBRID_CHARSET=en_US\n");
        script.append("export CTP_HOME=/home/cubrid-testtools/CTP\n");
        script.append("export init_path=/home/cubrid-testtools/CTP/shell/init_path\n\n");
        
        script.append("# Verify CUBRID installation\n");
        script.append("echo \"Verifying CUBRID installation...\"\n");
        script.append("cubrid_rel\n\n");
        
        // Verify expected build version if provided
        if (expectedBuildVersion != null && !expectedBuildVersion.isEmpty()) {
            script.append("# Verify expected build version\n");
            script.append("INSTALLED_VER=$(cubrid_rel 2>/dev/null | head -1)\n");
            script.append("echo \"Installed version: $INSTALLED_VER\"\n");
            script.append("if [[ \"$INSTALLED_VER\" != *\"").append(expectedBuildVersion).append("\"* ]]; then\n");
            script.append("    echo \"WARNING: Expected build version ").append(expectedBuildVersion);
            script.append(" not found in installed version\"\n");
            script.append("fi\n\n");
        }
        
        script.append("# Clean any previous database state\n");
        script.append("rm -rf /opt/cubrid/databases/*\n");
        script.append("mkdir -p /opt/cubrid/databases\n");
        script.append("touch /opt/cubrid/databases/databases.txt\n\n");
        
        script.append("# Run the test\n");
        script.append("cd /workspace/testcases\n");
        script.append("bash ").append(testScript).append("\n");
        script.append("TEST_EXIT=$?\n\n");
        
        // Copy result file to workspace
        String resultBase = testScript.endsWith(".sh") ? 
            testScript.substring(0, testScript.length() - 3) : testScript;
        script.append("# Copy result file if generated\n");
        script.append("if [ -f \"").append(resultBase).append(".result\" ]; then\n");
        script.append("    cp \"").append(resultBase).append(".result\" /workspace/\n");
        script.append("fi\n\n");
        
        script.append("exit $TEST_EXIT\n");
        
        return script.toString();
    }
}