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
        System.out.println("\n=== All VmSqlConfTest tests passed! ===");
    }

    private static void check(boolean ok, String name) {
        if (!ok) {
            throw new AssertionError("FAILED: " + name);
        }
        System.out.println("  ok " + name);
    }
}
