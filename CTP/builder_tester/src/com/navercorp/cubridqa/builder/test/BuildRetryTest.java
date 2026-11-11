package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.DockerBuildManager.BuildFailureSigns;

/**
 * A failed build is retried only when the failure may not come back: a compiler error or a commit
 * the clone lacks failed the same way twice (ecb26cd: two 2.5-minute builds for one answer).
 */
public class BuildRetryTest {

    public static void main(String[] args) {
        System.out.println("=== BuildRetryTest Suite ===\n");
        check(noRetry("[1978/2961] Building CXX object cubridmanager/server/...",
            "FAILED: cubridmanager/server/CMakeFiles/cub_manager.dir/src/cm_server_util.cpp.o ",
            "/work/cubrid-build/repo/cubridmanager/server/src/cm_server_util.cpp:3568:60: error: expected ')' before 'SCNu64'"),
            "compiler error -> no retry");
        check(noRetry("fatal: reference is not a tree: 0000000000000000000000000000000000000000"), "missing commit -> no retry");
        check(noRetry("CMake Error at CMakeLists.txt:120 (message):", "  Could not get count information from Git"),
            "CMake error -> no retry");
        check(!noRetry("FAILED: cs/x.c.o", "g++: fatal error: Killed signal terminated program cc1plus"),
            "compiler killed (out of memory) -> retry");
        check(!noRetry("FAILED: 3rdparty/Stamp/libexpat/libexpat-download",
            "  status_string: \"Couldn't resolve host name\""), "3rdparty download failure -> retry");
        check(noRetry("-- Build with openssl library: EXTERNAL", "[5/1404] Creating directories for 'libopenssl'",
            "  from /work/cubrid-build/repo/cubridmanager/server/external/openssl/linux_64/include/openssl/ssl.h:20,",
            "FAILED: sa/x.c.o", "x.c:1: error: this statement may fall through [-Werror=implicit-fallthrough=]"),
            "compiler error in a log that builds openssl -> no retry");
        check(!noRetry("FAILED: cs/x.c.o", "x.c:1: error: boom", "No space left on device"), "disk full -> retry");
        check(!noRetry("x.c:1: error: boom",
            "fatal: unable to access 'https://github.com/CUBRID/cubrid-cci/': OpenSSL SSL_connect: SSL_ERROR_SYSCALL"),
            "TLS failure -> retry");
        check(!noRetry("FAILED: 3rdparty/Stamp/libexpat/libexpat-download", "curl: (35) SSL connect error"),
            "curl SSL connect error -> retry");
        check(!noRetry("docker: Error response from daemon: ...", "[FATAL] Build failed"), "nothing known -> retry, as before");
        System.out.println("\n=== All BuildRetryTest tests passed! ===");
    }

    private static boolean noRetry(String... lines) {
        BuildFailureSigns signs = new BuildFailureSigns();
        for (String line : lines) {
            signs.observe(line);
        }
        return signs.deterministic();
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
        System.out.println("  ok: " + what);
    }
}
