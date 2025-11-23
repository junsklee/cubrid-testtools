package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.BuilderTask;
import com.navercorp.cubridqa.builder.scheduler.Assignment;
import com.navercorp.cubridqa.builder.scheduler.NodeDirectory;
import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.SchedulerService;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight harness to validate builder-side requeue logic for capacity rejections.
 *
 * This is an executable test (run main) rather than JUnit to keep dependencies minimal.
 */
public class RequeueLogicTest {

    public static void main(String[] args) throws Exception {
        // Build minimal config and BuilderTask instance (we won't call run()).
        Path tmpDir = Files.createTempDirectory("requeue_test");
        Path srcDir = Files.createDirectories(tmpDir.resolve("src"));
        Path tcDir = Files.createDirectories(tmpDir.resolve("tc"));
        Path cfg = tmpDir.resolve("builder.conf");
        Files.write(cfg, Arrays.asList(
                "cubrid_src_dir=" + srcDir,
                "shell_tc_dir=" + tcDir,
                // Enable unlimited retries to exercise -1 path
                "requeue_max_attempts=-1"
        ));
        BuilderConfig config = new BuilderConfig(cfg.toString());
        BuilderTask task = new BuilderTask("tid", new JSONObject(), config, null);

        // Reflective access to private helpers
        Method shouldRequeue = BuilderTask.class.getDeclaredMethod("shouldRequeue", JSONObject.class);
        shouldRequeue.setAccessible(true);
        Method requeueTest = BuilderTask.class.getDeclaredMethod("requeueTest",
                com.navercorp.cubridqa.builder.scheduler.Assignment.class,
                String.class,
                SchedulerService.class,
                Map.class,
                List.class,
                int.class,
                long.class,
                long.class,
                long.class,
                NodeDirectory.class);
        requeueTest.setAccessible(true);

        // Verify shouldRequeue looks at both message and error fields
        JSONObject cap = new JSONObject().put("status", "rejected").put("error", "No capacity - node oversubscribed");
        JSONObject other = new JSONObject().put("status", "rejected").put("message", "environment_error");
        if (!(Boolean) shouldRequeue.invoke(task, cap)) {
            throw new IllegalStateException("Expected capacity rejection to be requeued");
        }
        if ((Boolean) shouldRequeue.invoke(task, other)) {
            throw new IllegalStateException("Non-capacity rejection should not be requeued");
        }

        // Set up stub scheduler and node directory to observe requeue
        AtomicReference<TestInstance> requeued = new AtomicReference<>();
        SchedulerService stubScheduler = new SchedulerService(new NodeDirectory(Collections.emptyList()),
                null, null, 0.0, config) {
            @Override
            public synchronized void offer(List<TestInstance> tests) {
                if (!tests.isEmpty()) {
                    requeued.set(tests.get(0));
                }
            }
        };

        NodeSnapshot ready = NodeSnapshot.builder()
                .nodeId("n1:8090")
                .status("healthy")
                .maxConcurrentTests(2)
                .activeLimit(2)
                .runningTests(0)
                .maxWhileHeavy(2)
                .maxAfterHeavy(2)
                .build();
        NodeDirectory stubDir = new NodeDirectory(Collections.singletonList("n1:8090")) {
            @Override
            public void pollNow() {
                // no-op
            }

            @Override
            public NodeSnapshot getSnapshot(String nodeId) {
                return ready;
            }
        };

        // Build a dummy assignment
        TestInstance ti = TestInstance.builder()
                .testKey("t1")
                .commit("c1")
                .baseline("b1")
                .buildPackage("pkg")
                .build();
        Assignment assign = new Assignment(ti, "n1:8090", 0.0);

        Map<String, Integer> attempts = new ConcurrentHashMap<>();
        boolean queued = (Boolean) requeueTest.invoke(task, assign, "n1", stubScheduler, attempts,
                Collections.singletonList("n1:8090"), 3, 1L, 2L, 50L, stubDir);

        if (!queued || requeued.get() == null) {
            throw new IllegalStateException("Expected test to be requeued when capacity is available");
        }

        System.out.println("RequeueLogicTest passed");
    }
}
