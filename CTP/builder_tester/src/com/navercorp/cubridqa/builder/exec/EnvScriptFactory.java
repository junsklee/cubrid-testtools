package com.navercorp.cubridqa.builder.exec;

public class EnvScriptFactory {

    public static final String TESTCASE_MOUNT = "/workspace/testcases";

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
        script.append("set +e\n");
        script.append("sh \"").append(testScript).append("\"\n");
        script.append("TEST_EXIT_CODE=$?\n");
        script.append("set -e\n\n");

        script.append("exit $TEST_EXIT_CODE\n");
        return script.toString();
    }

    private static String normalizeRelativeDir(String relativeDir) {
        if (relativeDir == null) {
            return "";
        }
        String sanitized = relativeDir.trim().replace('\\', '/');
        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }
        while (sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized.equals(".") ? "" : sanitized;
    }

    public static String createDockerScript(String testScript, String testName, String expectedBuildVersion, String relativeTestDir, String ctpHome) {
        return createDockerScript(testScript, testName, expectedBuildVersion, relativeTestDir, ctpHome, TESTCASE_MOUNT);
    }

    public static String createDockerScript(String testScript, String testName, String expectedBuildVersion, String relativeTestDir, String ctpHome, String testcaseMountPoint) {
        String normalizedRelativeDir = normalizeRelativeDir(relativeTestDir);
        String initPath = ctpHome + "/shell/init_path";
        String testcaseRoot = (testcaseMountPoint == null || testcaseMountPoint.trim().isEmpty())
            ? TESTCASE_MOUNT
            : testcaseMountPoint.trim();

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n");
        script.append("set -x\n\n");

        script.append("# Resolve test directory inside the mounted shell testcase tree\n");
        script.append("export TESTCASE_ROOT=\"").append(testcaseRoot).append("\"\n");
        script.append("if [ ! -d \"$TESTCASE_ROOT\" ]; then\n");
        script.append("  echo \"ERROR: Shell testcase mount '$TESTCASE_ROOT' is unavailable\" >&2\n");
        script.append("  exit 2\n");
        script.append("fi\n");
        script.append("export TESTCASE_DIR=\"$TESTCASE_ROOT");
        if (!normalizedRelativeDir.isEmpty()) {
            script.append("/").append(normalizedRelativeDir);
        }
        script.append("\"\n");
        script.append("if [ ! -d \"$TESTCASE_DIR\" ]; then\n");
        script.append("  echo \"ERROR: Test directory '$TESTCASE_DIR' not found\" >&2\n");
        script.append("  exit 2\n");
        script.append("fi\n\n");

        script.append("# Extract CUBRID build\n");
        script.append("echo \"Extracting CUBRID build...\"\n");
        script.append("mkdir -p /tmp/cubrid_install\n");
        script.append("cd /tmp/cubrid_install\n");
        script.append("if tar -tf /workspace/build.tar.gz 2>/dev/null | grep -m1 -q '^_install/CUBRID/'; then\n");
        script.append("  echo \"Detected packaged _install/CUBRID layout\"\n");
        script.append("  tar -xzf /workspace/build.tar.gz --strip-components=2 _install/CUBRID\n");
        script.append("  CUBRID_ROOT=/tmp/cubrid_install\n");
        script.append("else\n");
        script.append("  tar -xzf /workspace/build.tar.gz\n");
        script.append("  if [ -d /tmp/cubrid_install/_install/CUBRID ]; then\n");
        script.append("    CUBRID_ROOT=/tmp/cubrid_install/_install/CUBRID\n");
        script.append("  else\n");
        script.append("    CUBRID_ROOT=$(find /tmp/cubrid_install -name \"bin\" -type d | head -1 | xargs dirname)\n");
        script.append("  fi\n");
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
        script.append("if [ -f /root/.cubrid.sh ]; then\n");
        script.append("  . /root/.cubrid.sh\n");
        script.append("fi\n");
        script.append("export CUBRID=\"$CUBRID_ROOT\"\n");
        script.append("export PATH=\"$CUBRID_ROOT/bin:" + initPath + ":" + ctpHome + "/bin:" + ctpHome + "/common/script:$PATH\"\n");
        script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$CUBRID_ROOT/cci/lib:$CUBRID_ROOT/lib64:" + ctpHome + "/common/lib:$LD_LIBRARY_PATH\"\n");
        script.append("export CUBRID_LANG=\"en_US\"\n");
        script.append("export CUBRID_CHARSET=\"en_US\"\n");
        script.append("export CTP_HOME=\"").append(ctpHome).append("\"\n");
        script.append("export init_path=\"").append(initPath).append("\"\n");
        script.append("export WORKSPACE=\"/workspace\"\n\n");

        String safeTestName = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        script.append("SAFE_TEST_NAME=\"").append(safeTestName).append("\"\n");
        script.append("RUNTIME_ROOT=\"/workspace/runtime\"\n");
        script.append("mkdir -p \"$RUNTIME_ROOT\"\n");
        script.append("TEST_RUNTIME_DIR=\"$RUNTIME_ROOT/${SAFE_TEST_NAME}_$(date +%s%N)\"\n");
        script.append("mkdir -p \"$TEST_RUNTIME_DIR/databases\"\n");
        script.append(": > \"$TEST_RUNTIME_DIR/databases/databases.txt\"\n");
        script.append("export CUBRID_DATABASES=\"$TEST_RUNTIME_DIR/databases\"\n");
        script.append("ORIGINAL_TESTCASE_DIR=\"$TESTCASE_DIR\"\n");
        script.append("MOUNT_MODE=\"\"\n");
        script.append("OVERLAY_ERROR_LOG=/tmp/overlay_setup.err\n");
        script.append(": > \"$OVERLAY_ERROR_LOG\"\n");
        script.append("cleanup_runtime() {\n");
        script.append("  set +e\n");
        script.append("  if [ \"$MOUNT_MODE\" = \"overlay\" ]; then\n");
        script.append("    cd / >/dev/null 2>&1 || true\n");
        script.append("    umount \"$ORIGINAL_TESTCASE_DIR\" >/dev/null 2>&1 || true\n");
        script.append("    umount \"$TEST_RUNTIME_DIR/merged\" >/dev/null 2>&1 || true\n");
        script.append("  elif [ \"$MOUNT_MODE\" = \"bind\" ]; then\n");
        script.append("    cd / >/dev/null 2>&1 || true\n");
        script.append("    umount \"$ORIGINAL_TESTCASE_DIR\" >/dev/null 2>&1 || true\n");
        script.append("  fi\n");
        script.append("  if [ \"$KEEP_RUNTIME_DIR\" != \"1\" ] && [ -n \"$TEST_RUNTIME_DIR\" ] && [ -d \"$TEST_RUNTIME_DIR\" ]; then\n");
        script.append("    rm -rf \"$TEST_RUNTIME_DIR\"\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("trap cleanup_runtime EXIT\n\n");

        script.append("make_writable_testcase_dir() {\n");
        script.append("  mkdir -p \"$TEST_RUNTIME_DIR/upper\" \"$TEST_RUNTIME_DIR/work\" \"$TEST_RUNTIME_DIR/merged\"\n");
        script.append("  if mount -t overlay overlay -o lowerdir=\"$TESTCASE_DIR\",upperdir=\"$TEST_RUNTIME_DIR/upper\",workdir=\"$TEST_RUNTIME_DIR/work\" \"$TEST_RUNTIME_DIR/merged\" 2>>\"$OVERLAY_ERROR_LOG\"; then\n");
        script.append("    if mount --bind \"$TEST_RUNTIME_DIR/merged\" \"$ORIGINAL_TESTCASE_DIR\" 2>>\"$OVERLAY_ERROR_LOG\"; then\n");
        script.append("      MOUNT_MODE=\"overlay\"\n");
        script.append("      return 0\n");
        script.append("    else\n");
        script.append("      umount \"$TEST_RUNTIME_DIR/merged\" >/dev/null 2>&1 || true\n");
        script.append("    fi\n");
        script.append("  fi\n");
        script.append("  if [ -s \"$OVERLAY_ERROR_LOG\" ]; then\n");
        script.append("    echo \"WARNING: overlayfs unavailable\" >&2\n");
        script.append("    cat \"$OVERLAY_ERROR_LOG\" >&2 || true\n");
        script.append("  else\n");
        script.append("    echo \"WARNING: overlayfs unavailable, falling back to writable copy\" >&2\n");
        script.append("  fi\n");
        script.append("  FALLBACK_DIR=\"$TEST_RUNTIME_DIR/case_copy\"\n");
        script.append("  rm -rf \"$FALLBACK_DIR\"\n");
        script.append("  mkdir -p \"$FALLBACK_DIR\"\n");
        script.append("  if command -v rsync >/dev/null 2>&1; then\n");
        script.append("    rsync -a \"$TESTCASE_DIR\"/ \"$FALLBACK_DIR\"/\n");
        script.append("  else\n");
        script.append("    cp -a \"$TESTCASE_DIR\"/. \"$FALLBACK_DIR\"/\n");
        script.append("  fi\n");
        script.append("  if mount --bind \"$FALLBACK_DIR\" \"$ORIGINAL_TESTCASE_DIR\" 2>>\"$OVERLAY_ERROR_LOG\"; then\n");
        script.append("    MOUNT_MODE=\"bind\"\n");
        script.append("    return 0\n");
        script.append("  fi\n");
        script.append("  echo \"WARNING: bind mount unavailable; running directly from copied directory $FALLBACK_DIR\" >&2\n");
        script.append("  export TESTCASE_DIR=\"$FALLBACK_DIR\"\n");
        script.append("}\n");
        script.append("make_writable_testcase_dir\n\n");

        script.append("# Emit debug env snapshot for docker exec sessions\n");
        script.append("cat > /workspace/debug_env.sh <<'EOS'\n");
        script.append("export CUBRID=\"$CUBRID_ROOT\"\n");
        script.append("export CUBRID_DATABASES=\"$CUBRID_DATABASES\"\n");
        script.append("export PATH=\"$CUBRID_ROOT/bin:$PATH\"\n");
        script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$CUBRID_ROOT/cci/lib:$CUBRID_ROOT/lib64:$LD_LIBRARY_PATH\"\n");
        script.append("export CTP_HOME=\"").append(ctpHome).append("\"\n");
        script.append("export init_path=\"").append(initPath).append("\"\n");
        script.append("export WORKSPACE=\"/workspace\"\n");
        script.append("EOS\n");
        script.append("chmod +x /workspace/debug_env.sh\n\n");

        script.append("# Ensure configuration backups exist for restoration\n");
        script.append("for conf_file in /opt/cubrid/conf/cubrid.conf \\\n");
        script.append("  /opt/cubrid/conf/cubrid_broker.conf \\\n");
        script.append("  /opt/cubrid/conf/cubrid_gateway.conf \\\n");
        script.append("  /opt/cubrid/conf/cubrid_ha.conf; do\n");
        script.append("  if [ -f \"$conf_file\" ] && [ ! -f \"$conf_file.org\" ]; then\n");
        script.append("    cp \"$conf_file\" \"$conf_file.org\"\n");
        script.append("  fi\n");
        script.append("done\n\n");

        script.append("# Verify installation\n");
        script.append("echo \"Verifying CUBRID installation...\"\n");
        script.append("if ! cubrid_rel; then\n");
        script.append("    echo \"ERROR: CUBRID verification failed\"\n");
        script.append("    exit 1\n");
        script.append("fi\n\n");

        script.append("mkdir -p \"$CUBRID_DATABASES\"\n");
        script.append("mkdir -p \"$CUBRID/log/server\"\n");
        script.append("rm -f \"$CUBRID/log/server/*\" || true\n");
        script.append(": > \"$CUBRID_DATABASES/databases.txt\"\n\n");

        if (expectedBuildVersion != null && !expectedBuildVersion.isEmpty()) {
            script.append("# Verify expected build version\n");
            script.append("INSTALLED_VER=$(cubrid_rel 2>/dev/null | head -1)\n");
            script.append("echo \"Installed version: $INSTALLED_VER\"\n");
            script.append("if [[ \"$INSTALLED_VER\" != *\"").append(expectedBuildVersion).append("\"* ]]; then\n");
            script.append("    echo \"WARNING: Expected build version ").append(expectedBuildVersion);
            script.append(" not found in installed version\"\n");
            script.append("fi\n\n");
        }

        script.append("# Run test\n");
        script.append("cd \"$TESTCASE_DIR\"\n");
        script.append("set +e\n");
        script.append("sh ").append(testScript).append("\n");
        script.append("TEST_EXIT=$?\n");
        script.append("set -e\n\n");

        String resultBase = testScript.endsWith(".sh") ?
            testScript.substring(0, testScript.length() - 3) :
            (testScript.contains(".") ? testScript.substring(0, testScript.lastIndexOf('.')) : testScript);
        script.append("# Copy result file if generated by test\n");
        script.append("if [ -f \"").append(resultBase).append(".result\" ]; then\n");
        script.append("    cp \"").append(resultBase).append(".result\" /workspace/\n");
        script.append("fi\n\n");

        script.append("exit $TEST_EXIT\n");
        return script.toString();
    }

    public static String createDockerOptimizedScript(String testScript, String testName, String expectedBuildVersion, String relativeTestDir, String ctpHome) {
        return createDockerOptimizedScript(testScript, testName, expectedBuildVersion, relativeTestDir, ctpHome, TESTCASE_MOUNT);
    }

    public static String createDockerOptimizedScript(String testScript, String testName, String expectedBuildVersion, String relativeTestDir, String ctpHome, String testcaseMountPoint) {
        String normalizedRelativeDir = normalizeRelativeDir(relativeTestDir);
        String initPath = ctpHome + "/shell/init_path";
        String testcaseRoot = (testcaseMountPoint == null || testcaseMountPoint.trim().isEmpty())
            ? TESTCASE_MOUNT
            : testcaseMountPoint.trim();

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n");
        script.append("set -x\n\n");

        script.append("# Resolve test directory inside the mounted shell testcase tree\n");
        script.append("export TESTCASE_ROOT=\"").append(testcaseRoot).append("\"\n");
        script.append("if [ ! -d \"$TESTCASE_ROOT\" ]; then\n");
        script.append("  echo \"ERROR: Shell testcase mount '$TESTCASE_ROOT' is unavailable\" >&2\n");
        script.append("  exit 2\n");
        script.append("fi\n");
        script.append("export TESTCASE_DIR=\"$TESTCASE_ROOT");
        if (!normalizedRelativeDir.isEmpty()) {
            script.append("/").append(normalizedRelativeDir);
        }
        script.append("\"\n");
        script.append("if [ ! -d \"$TESTCASE_DIR\" ]; then\n");
        script.append("  echo \"ERROR: Test directory '$TESTCASE_DIR' not found\" >&2\n");
        script.append("  exit 2\n");
        script.append("fi\n\n");

        script.append("# CUBRID is pre-installed in /opt/cubrid\n");
        script.append("export CUBRID=/opt/cubrid\n");
        script.append("export PATH=\"/opt/cubrid/bin:" + initPath + ":" + ctpHome + "/bin:" + ctpHome + "/common/script:$PATH\"\n");
        script.append("export LD_LIBRARY_PATH=\"/opt/cubrid/lib:/opt/cubrid/cci/lib:" + ctpHome + "/common/lib:$LD_LIBRARY_PATH\"\n");
        script.append("export CUBRID_LANG=en_US\n");
        script.append("export CUBRID_CHARSET=en_US\n");
        script.append("export CTP_HOME=\"").append(ctpHome).append("\"\n");
        script.append("export init_path=\"").append(initPath).append("\"\n");
        script.append("export WORKSPACE=\"/workspace\"\n\n");

        String safeTestNameOpt = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        script.append("SAFE_TEST_NAME=\"").append(safeTestNameOpt).append("\"\n");
        script.append("RUNTIME_ROOT=\"/workspace/runtime\"\n");
        script.append("mkdir -p \"$RUNTIME_ROOT\"\n");
        script.append("TEST_RUNTIME_DIR=\"$RUNTIME_ROOT/${SAFE_TEST_NAME}_$(date +%s%N)\"\n");
        script.append("mkdir -p \"$TEST_RUNTIME_DIR/databases\"\n");
        script.append(": > \"$TEST_RUNTIME_DIR/databases/databases.txt\"\n");
        script.append("export CUBRID_DATABASES=\"$TEST_RUNTIME_DIR/databases\"\n");
        script.append("ORIGINAL_TESTCASE_DIR=\"$TESTCASE_DIR\"\n");
        script.append("MOUNT_MODE=\"\"\n");
        script.append("OVERLAY_ERROR_LOG=/tmp/overlay_setup.err\n");
        script.append(": > \"$OVERLAY_ERROR_LOG\"\n");
        script.append("cleanup_runtime() {\n");
        script.append("  set +e\n");
        script.append("  if [ \"$MOUNT_MODE\" = \"overlay\" ]; then\n");
        script.append("    cd / >/dev/null 2>&1 || true\n");
        script.append("    umount \"$ORIGINAL_TESTCASE_DIR\" >/dev/null 2>&1 || true\n");
        script.append("    umount \"$TEST_RUNTIME_DIR/merged\" >/dev/null 2>&1 || true\n");
        script.append("  elif [ \"$MOUNT_MODE\" = \"bind\" ]; then\n");
        script.append("    cd / >/dev/null 2>&1 || true\n");
        script.append("    umount \"$ORIGINAL_TESTCASE_DIR\" >/dev/null 2>&1 || true\n");
        script.append("  fi\n");
        script.append("  if [ \"$KEEP_RUNTIME_DIR\" != \"1\" ] && [ -n \"$TEST_RUNTIME_DIR\" ] && [ -d \"$TEST_RUNTIME_DIR\" ]; then\n");
        script.append("    rm -rf \"$TEST_RUNTIME_DIR\"\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("trap cleanup_runtime EXIT\n\n");

        script.append("make_writable_testcase_dir() {\n");
        script.append("  mkdir -p \"$TEST_RUNTIME_DIR/upper\" \"$TEST_RUNTIME_DIR/work\" \"$TEST_RUNTIME_DIR/merged\"\n");
        script.append("  if mount -t overlay overlay -o lowerdir=\"$TESTCASE_DIR\",upperdir=\"$TEST_RUNTIME_DIR/upper\",workdir=\"$TEST_RUNTIME_DIR/work\" \"$TEST_RUNTIME_DIR/merged\" 2>>\"$OVERLAY_ERROR_LOG\"; then\n");
        script.append("    if mount --bind \"$TEST_RUNTIME_DIR/merged\" \"$ORIGINAL_TESTCASE_DIR\" 2>>\"$OVERLAY_ERROR_LOG\"; then\n");
        script.append("      MOUNT_MODE=\"overlay\"\n");
        script.append("      return 0\n");
        script.append("    else\n");
        script.append("      umount \"$TEST_RUNTIME_DIR/merged\" >/dev/null 2>&1 || true\n");
        script.append("    fi\n");
        script.append("  fi\n");
        script.append("  if [ -s \"$OVERLAY_ERROR_LOG\" ]; then\n");
        script.append("    echo \"WARNING: overlayfs unavailable\" >&2\n");
        script.append("    cat \"$OVERLAY_ERROR_LOG\" >&2 || true\n");
        script.append("  else\n");
        script.append("    echo \"WARNING: overlayfs unavailable, falling back to writable copy\" >&2\n");
        script.append("  fi\n");
        script.append("  FALLBACK_DIR=\"$TEST_RUNTIME_DIR/case_copy\"\n");
        script.append("  rm -rf \"$FALLBACK_DIR\"\n");
        script.append("  mkdir -p \"$FALLBACK_DIR\"\n");
        script.append("  if command -v rsync >/dev/null 2>&1; then\n");
        script.append("    rsync -a \"$TESTCASE_DIR\"/ \"$FALLBACK_DIR\"/\n");
        script.append("  else\n");
        script.append("    cp -a \"$TESTCASE_DIR\"/. \"$FALLBACK_DIR\"/\n");
        script.append("  fi\n");
        script.append("  if mount --bind \"$FALLBACK_DIR\" \"$ORIGINAL_TESTCASE_DIR\" 2>>\"$OVERLAY_ERROR_LOG\"; then\n");
        script.append("    MOUNT_MODE=\"bind\"\n");
        script.append("    return 0\n");
        script.append("  fi\n");
        script.append("  echo \"WARNING: bind mount unavailable; running directly from copied directory $FALLBACK_DIR\" >&2\n");
        script.append("  export TESTCASE_DIR=\"$FALLBACK_DIR\"\n");
        script.append("}\n");
        script.append("make_writable_testcase_dir\n\n");

        script.append("# Ensure configuration backups exist for restoration\n");
        script.append("for conf_file in /opt/cubrid/conf/cubrid.conf \\\n");
        script.append("  /opt/cubrid/conf/cubrid_broker.conf \\\n");
        script.append("  /opt/cubrid/conf/cubrid_gateway.conf \\\n");
        script.append("  /opt/cubrid/conf/cubrid_ha.conf; do\n");
        script.append("  if [ -f \"$conf_file\" ] && [ ! -f \"$conf_file.org\" ]; then\n");
        script.append("    cp \"$conf_file\" \"$conf_file.org\"\n");
        script.append("  fi\n");
        script.append("done\n\n");

        script.append("# Verify CUBRID installation\n");
        script.append("echo \"Verifying CUBRID installation...\"\n");
        script.append("cubrid_rel\n\n");

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
        script.append("rm -rf \"$CUBRID_DATABASES\"/*\n");
        script.append("mkdir -p \"$CUBRID_DATABASES\"\n");
        script.append("touch \"$CUBRID_DATABASES/databases.txt\"\n\n");

        script.append("# Run the test\n");
        script.append("cd \"$TESTCASE_DIR\"\n");
        script.append("set +e\n");
        script.append("sh ").append(testScript).append("\n");
        script.append("TEST_EXIT=$?\n");
        script.append("set -e\n\n");

        String resultBase = testScript.endsWith(".sh") ?
            testScript.substring(0, testScript.length() - 3) :
            (testScript.contains(".") ? testScript.substring(0, testScript.lastIndexOf('.')) : testScript);
        script.append("# Copy result file if generated\n");
        script.append("if [ -f \"").append(resultBase).append(".result\" ]; then\n");
        script.append("    cp \"").append(resultBase).append(".result\" /workspace/\n");
        script.append("fi\n\n");

        script.append("exit $TEST_EXIT\n");
        return script.toString();
    }
}
