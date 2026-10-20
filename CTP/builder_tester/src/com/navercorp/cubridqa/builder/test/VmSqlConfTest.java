package com.navercorp.cubridqa.builder.test;

import java.lang.reflect.Method;

import com.navercorp.cubridqa.builder.exec.VmSqlExecutor;

/**
 * VM SQL cases are provisioned with the conf QAHome regression uses for the build's version
 * (job.conf: MKEY_CONFIG=sql_dont_reuse_heap.conf for 10.1/10.2, else run_sql.sh's sql_local.conf).
 */
public class VmSqlConfTest {
    private static final String RULES = "10.1.*,10.2.*=sql_dont_reuse_heap.conf";

    public static void main(String[] args) throws Exception {
        System.out.println("=== VmSqlConfTest Suite ===\n");
        Method select = VmSqlExecutor.class.getDeclaredMethod("selectSqlConf", String.class, String.class, String.class);
        Method parse = VmSqlExecutor.class.getDeclaredMethod("parseVersion", String.class);
        select.setAccessible(true);
        parse.setAccessible(true);

        check("10.1.5.7809".equals(parse.invoke(null, "CUBRID 10.1 (10.1.5.7809-5a2a9d6) (64bit release build for Linux) (Mar 1 2021)")), "parse 10.1");
        check("11.0.17.0000".equals(parse.invoke(null, "\nCUBRID 11.0 (11.0.17.0000-0000000) (64bit release build for Linux) (Oct 19 2026 14:22:32)\n")), "parse 11.0");
        check("11.5.0.2565".equals(parse.invoke(null, "CUBRID 11.5.0 (11.5.0.2565-b319ce1) (64bit debug build for Linux)")), "parse debug");
        check(parse.invoke(null, "cubrid_rel: error while loading shared libraries") == null, "parse failure -> null");

        check("sql_dont_reuse_heap.conf".equals(select.invoke(null, "10.1.5.7809", RULES, "sql_local.conf")), "10.1 -> dont_reuse_heap");
        check("sql_dont_reuse_heap.conf".equals(select.invoke(null, "10.2.12.8988", RULES, "sql_local.conf")), "10.2 -> dont_reuse_heap");
        check("sql_local.conf".equals(select.invoke(null, "11.0.17.0000", RULES, "sql_local.conf")), "11.0 -> default");
        check("sql_local.conf".equals(select.invoke(null, "11.5.0.2565", RULES, "sql_local.conf")), "11.5 -> default");
        check("sql_local.conf".equals(select.invoke(null, "10.12.0.0001", RULES, "sql_local.conf")), "10.12 is not 10.1.*");
        check("sql_local.conf".equals(select.invoke(null, null, RULES, "sql_local.conf")), "unknown version -> default");
        check("a.conf".equals(select.invoke(null, "11.4.1.1", "10.*=x.conf; 11.4.*,11.5.*=a.conf", "d.conf")), "second rule");
        check("d.conf".equals(select.invoke(null, "11.4.1.1", "", "d.conf")), "no rules -> default");
        resolveOrder();
        System.out.println("\n=== All VmSqlConfTest tests passed! ===");
    }

    /** VM conf, else Builder-Tester's copy of the regression conf, else sql.conf with a warning. */
    static void resolveOrder() throws Exception {
        Method resolve = VmSqlExecutor.class.getDeclaredMethod("resolveSqlConf", java.nio.file.Path.class, String.class,
            java.nio.file.Path.class, String.class, StringBuilder.class, java.util.logging.Logger.class);
        resolve.setAccessible(true);
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("vmsqlconf");
        java.nio.file.Path ctp = root.resolve("CTP");
        java.nio.file.Path ws = root.resolve("ws");
        java.nio.file.Files.createDirectories(ctp.resolve("conf"));
        java.nio.file.Files.write(ctp.resolve("conf/sql.conf"), "[sql]\nstock=1\n".getBytes());
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("t");

        StringBuilder pre = new StringBuilder();
        java.nio.file.Path got = (java.nio.file.Path) resolve.invoke(null, ctp, "sql_local.conf", ws, "11.0.17.0000", pre, log);
        String bundled = new String(java.nio.file.Files.readAllBytes(got));
        check(got.startsWith(ws) && bundled.contains("cubrid_port_id=1285") && bundled.contains("lock_timeout=10sec"),
            "VM lacks sql_local.conf -> bundled regression copy: " + got);
        check(pre.toString().contains("Builder-Tester's copy") && !pre.toString().contains("WARNING"), "bundled copy noted, no warning");

        java.nio.file.Files.write(ctp.resolve("conf/sql_local.conf"), "[sql]\nvm=1\n".getBytes());
        got = (java.nio.file.Path) resolve.invoke(null, ctp, "sql_local.conf", ws, "11.0.17.0000", new StringBuilder(), log);
        check(got.equals(ctp.resolve("conf/sql_local.conf")), "VM's own sql_local.conf wins");

        pre = new StringBuilder();
        got = (java.nio.file.Path) resolve.invoke(null, ctp, "sql_dont_reuse_heap.conf", ws, "10.2.12.8988", pre, log);
        check(got.equals(ctp.resolve("conf/sql.conf")) && pre.toString().contains("WARNING"), "not on VM nor bundled -> sql.conf + warning");

        got = (java.nio.file.Path) resolve.invoke(null, ctp, "../../etc/passwd", ws, "11.0.17.0000", new StringBuilder(), log);
        check(got.equals(ctp.resolve("conf/sql.conf")), "a path outside conf/ is never used");

        java.nio.file.Files.delete(ctp.resolve("conf/sql.conf"));
        try {
            resolve.invoke(null, ctp, "sql_dont_reuse_heap.conf", ws, "10.2.12.8988", new StringBuilder(), log);
            check(false, "should fail without any conf");
        } catch (java.lang.reflect.InvocationTargetException e) {
            check(e.getCause() instanceof java.io.IOException, "no conf at all -> error");
        }
    }

    private static void check(boolean ok, String name) {
        if (!ok) {
            throw new AssertionError("FAILED: " + name);
        }
        System.out.println("  ok " + name);
    }
}
