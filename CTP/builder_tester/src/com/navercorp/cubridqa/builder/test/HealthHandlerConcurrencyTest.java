package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.tester.ActualSampler;
import com.navercorp.cubridqa.builder.tester.HealthHandler;
import com.navercorp.cubridqa.builder.tester.NodeCapacity;
import com.navercorp.cubridqa.builder.tester.TestOrchestrator;
import com.navercorp.cubridqa.builder.tester.HttpResponseWriter;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smoke test to ensure the tester advertises the correct concurrency cap
 * before and after heavy tests drain. Uses the same reflection pattern as
 * other lightweight tests to avoid HTTP plumbing.
 */
public class HealthHandlerConcurrencyTest {

    public static void main(String[] args) throws Exception {
        Path tmpDir = Files.createTempDirectory("tester_concurrency");
        Path configFile = tmpDir.resolve("tester.conf");

        // Minimal config with distinct heavy/post-heavy limits
        String cfg = ""
                + "cubrid_src_dir=" + tmpDir + "\n"
                + "shell_tc_dir=" + tmpDir + "\n"
                + "work_dir=" + tmpDir + "\n"
                + "max_concurrent_tests_heavy_queue=2\n"
                + "max_concurrent_tests_post_heavy=5\n";
        Files.write(configFile, cfg.getBytes());

        Config config = new Config(configFile.toString());
        NodeCapacity capacity = NodeCapacity.measure(config.getWorkDir(), config);

        // Stub sampler that returns empty utilization without touching Docker
        ActualSampler sampler = new ActualSampler(config) {
            @Override
            public com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot sampleCurrentUtilization() {
                return com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot.empty();
            }
        };

        TestOrchestrator orchestrator = new TestOrchestrator(
                config,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null
        );

        HealthHandler handler = new HealthHandler(config, new HttpResponseWriter(), capacity, orchestrator, sampler);
        Method buildHealth = HealthHandler.class.getDeclaredMethod("buildHealthResponse");
        buildHealth.setAccessible(true);

        // Simulate heavy tests inflight
        orchestrator.tryAcquireSlot(true); // heavy slot #1
        JSONObject heavyHealth = (JSONObject) buildHealth.invoke(handler);
        int heavyAdvertised = heavyHealth.getInt("maxConcurrentTests");
        int heavyMax = heavyHealth.getJSONObject("concurrency").getInt("max");
        if (heavyAdvertised != 2) {
            throw new IllegalStateException("Expected heavy cap 2, got " + heavyAdvertised);
        }
        if (heavyMax != 2) {
            throw new IllegalStateException("Expected concurrency.max heavy cap 2, got " + heavyMax);
        }

        // Drain heavies and expect post-heavy limit
        orchestrator.releaseSlot(true);
        JSONObject postHealth = (JSONObject) buildHealth.invoke(handler);
        int postAdvertised = postHealth.getInt("maxConcurrentTests");
        int postMax = postHealth.getJSONObject("concurrency").getInt("max");
        if (postAdvertised != 5) {
            throw new IllegalStateException("Expected post-heavy cap 5, got " + postAdvertised);
        }
        if (postMax != 5) {
            throw new IllegalStateException("Expected concurrency.max post-heavy cap 5, got " + postMax);
        }

        System.out.println("HealthHandlerConcurrencyTest passed");
    }
}
