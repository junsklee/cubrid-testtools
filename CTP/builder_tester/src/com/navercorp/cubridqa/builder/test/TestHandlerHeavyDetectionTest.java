package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.tester.NodeCapacity;
import com.navercorp.cubridqa.builder.tester.TestHandler;
import com.navercorp.cubridqa.builder.tester.TestOrchestrator;
import com.navercorp.cubridqa.builder.tester.demand.PredictedDemand;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Lightweight smoke test to verify heavy detection on the tester:
 * - High IO + long duration ⇒ heavy
 * - Low IO + long duration ⇒ not heavy
 * - High IO but short duration ⇒ not heavy (respects mice threshold)
 */
public class TestHandlerHeavyDetectionTest {

    public static void main(String[] args) throws Exception {
        Path tmpDir = Files.createTempDirectory("heavy_detect");
        Path cfgFile = tmpDir.resolve("tester.conf");

        // Minimal config with mice threshold 30s and heavy IO threshold 20 MB/s
        String cfg = ""
                + "cubrid_src_dir=" + tmpDir + "\n"
                + "shell_tc_dir=" + tmpDir + "\n"
                + "work_dir=" + tmpDir + "\n"
                + "max_concurrent_tests_heavy_queue=2\n"
                + "max_concurrent_tests_post_heavy=8\n"
                + "scheduling_mice_threshold_ms=30000\n"
                + "scheduling_io_heavy_threshold=20\n";
        Files.write(cfgFile, cfg.getBytes());

        Config config = new Config(cfgFile.toString());
        NodeCapacity capacity = NodeCapacity.measure(config.getWorkDir(), config);
        TestOrchestrator orchestrator = new TestOrchestrator(
                config, null, null, null, false, null, null, null, null);
        Logger logger = Logger.getLogger(TestHandlerHeavyDetectionTest.class.getName());
        TestHandler handler = new TestHandler(config, orchestrator, capacity, logger);

        Method isHeavy = TestHandler.class.getDeclaredMethod("isHeavyTest", PredictedDemand.class);
        isHeavy.setAccessible(true);

        // Heavy candidate: 40s duration, 30 MB/s total IO
        JSONObject heavyReq = new JSONObject()
                .put("predicted", new JSONObject()
                        .put("durationMs", 40_000)
                        .put("ioReadBytesPerSec", 15L * 1024 * 1024)
                        .put("ioWriteBytesPerSec", 15L * 1024 * 1024));
        boolean heavy = (boolean) isHeavy.invoke(handler, PredictedDemand.fromRequest(heavyReq, null));
        if (!heavy) {
            throw new IllegalStateException("Expected heavy test to be detected (40s, 30MB/s total IO)");
        }

        // Non-heavy IO: 40s duration, 10 MB/s total IO
        JSONObject lightReq = new JSONObject()
                .put("predicted", new JSONObject()
                        .put("durationMs", 40_000)
                        .put("ioReadBytesPerSec", 5L * 1024 * 1024)
                        .put("ioWriteBytesPerSec", 5L * 1024 * 1024));
        boolean light = (boolean) isHeavy.invoke(handler, PredictedDemand.fromRequest(lightReq, null));
        if (light) {
            throw new IllegalStateException("Did not expect heavy for low IO (10MB/s total)");
        }

        // Short duration should not be heavy even if IO is high
        JSONObject shortReq = new JSONObject()
                .put("predicted", new JSONObject()
                        .put("durationMs", 10_000)
                        .put("ioReadBytesPerSec", 30L * 1024 * 1024)
                        .put("ioWriteBytesPerSec", 30L * 1024 * 1024));
        boolean shortHeavy = (boolean) isHeavy.invoke(handler, PredictedDemand.fromRequest(shortReq, null));
        if (shortHeavy) {
            throw new IllegalStateException("Did not expect heavy for short duration (10s) even with high IO");
        }

        System.out.println("TestHandlerHeavyDetectionTest passed");
    }
}
