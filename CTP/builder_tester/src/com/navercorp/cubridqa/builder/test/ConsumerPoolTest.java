package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.consumer.ConsumerPool;
import com.navercorp.cubridqa.builder.consumer.ConsumerRunner;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Consumer execution: cases wait in ConsumerPool until a runner on a consumer VM leases them; a
 * slot message is published per VM wanted; a silent VM's case goes to another VM; cancel and
 * timeouts reach the waiting BuilderTask thread and the runner.
 */
public class ConsumerPoolTest {
    private static final Logger LOG = Logger.getLogger("ConsumerPoolTest");

    static final class FakeQueue implements ConsumerPool.Publisher {
        final List<Map<String, String>> published = Collections.synchronizedList(new ArrayList<>());
        final List<String> purged = Collections.synchronizedList(new ArrayList<>());
        volatile boolean fail;
        /** Slots whose message a consumer took (no longer in the queue). */
        final java.util.Set<String> taken = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean allTaken;

        @Override
        public void publish(Map<String, String> properties, String text) throws Exception {
            if (fail) {
                throw new IOException("broker down");
            }
            published.add(properties);
        }

        @Override
        public void purge(String requestId) {
            purged.add(requestId);
        }

        @Override
        public java.util.Set<String> queuedSlots(String requestId) {
            java.util.Set<String> queued = new java.util.HashSet<>();
            synchronized (published) {
                for (Map<String, String> m : published) {
                    String slot = m.get("BT_SLOT");
                    if (requestId.equals(m.get("BT_REQUEST_ID")) && !allTaken && !taken.contains(slot)) {
                        queued.add(slot);
                    }
                }
            }
            return queued;
        }

        String slot(int i) {
            return published.get(i).get("BT_SLOT");
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ConsumerPoolTest Suite ===\n");
        leaseRunAndComplete();
        slotsCappedAtMaxParallel();
        unknownSlotAndClosedRequestGetNothing();
        silentRunnerLosesTheCase();
        cancelWithdrawsLeasedCase();
        pickupTimeout();
        publishFailureFailsTheCase();
        closePurgesUnclaimedMessages();
        runnerDiesBetweenCases();
        messageTakenButRunnerNeverChecksIn();
        lostMessagesFailTheRequest();
        runnersDyingBeforeFirstCaseFailTheRequest();
        heartbeatAndResultAfterRequeueRefused();
        multipartRoundTrip();
        System.out.println("\n=== All ConsumerPoolTest tests passed! ===");
    }

    static void leaseRunAndComplete() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://builder:8089", 4, 60_000, 60_000);
        pool.open("req_a", "bt_req_a");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ConsumerPool.Relay> waiting = ex.submit(() -> pool.run("req_a",
            new JSONObject().put("testName", "t1"), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 1, "one message published");
        Map<String, String> message = queue.published.get(0);
        check("req_a".equals(message.get("BT_REQUEST_ID")) && "http://builder:8089".equals(message.get("BT_BUILDER_URL")),
            "message names request and builder");
        check(message.get("BUILD_ID") != null && message.get("BUILD_SCENARIOS") != null && message.get("BUILD_SVN_BRANCH") != null,
            "message carries what start_consumer.sh requires");
        for (String value : message.values()) {
            check(!value.contains(" "), "single-word property: " + value);
        }
        check(pool.claim("req_a", queue.slot(0)), "runner claims its slot");
        ConsumerPool.Lease lease = pool.lease("req_a", queue.slot(0), "qa@vm1", 2000);
        check(lease != null && "t1".equals(lease.testRequest.getString("testName")), "runner leases the case");
        check(!pool.heartbeat(lease.jobId), "heartbeat keeps going");
        check(pool.complete(lease.jobId, new ConsumerPool.Relay(200, "application/json", "{\"status\":\"pass\"}".getBytes())),
            "result accepted");
        ConsumerPool.Relay relay = waiting.get(5, TimeUnit.SECONDS);
        check(relay.status == 200 && new String(relay.body).contains("pass"), "waiting thread gets the relay");
        check(!pool.complete(lease.jobId, new ConsumerPool.Relay(200, null, new byte[0])), "second result refused");
        check(pool.lease("req_a", queue.slot(0), "qa@vm1", 300) == null, "no more cases: runner told to exit");
        check(pool.heartbeat(lease.jobId), "finished job: heartbeat says stop");
        pool.close("req_a");
        ex.shutdownNow();
    }

    static void slotsCappedAtMaxParallel() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 2, 60_000, 60_000);
        pool.open("req_b", "bt_req_b");
        ExecutorService ex = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            final int n = i;
            ex.submit(() -> pool.run("req_b", new JSONObject().put("testName", "t" + n), 60_000, () -> false, LOG));
        }
        waitFor(() -> queue.published.size() == 2, "two messages for three cases at max_parallel 2");
        Thread.sleep(300);
        check(queue.published.size() == 2, "no third message");
        // One runner works through cases in turn
        ConsumerPool.Lease first = pool.lease("req_b", queue.slot(0), "vm1", 1000);
        ConsumerPool.Lease second = pool.lease("req_b", queue.slot(0), "vm1", 1000);
        check(first != null && second != null && !first.jobId.equals(second.jobId), "one slot leases several cases");
        pool.close("req_b");
        ex.shutdownNow();
    }

    static void unknownSlotAndClosedRequestGetNothing() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 2, 60_000, 60_000);
        check(pool.lease("req_none", "0123456789abcdef0123456789abcdef", "vm", 100) == null, "unknown request");
        pool.open("req_c", "bt_req_c");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        ex.submit(() -> pool.run("req_c", new JSONObject(), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 1, "published");
        check(pool.lease("req_c", "ffffffffffffffffffffffffffffffff", "vm", 100) == null, "slot that was never published");
        pool.close("req_c");
        check(!pool.claim("req_c", queue.slot(0)), "closed request inactive");
        check(pool.lease("req_c", queue.slot(0), "vm", 100) == null, "closed request has nothing");
        ex.shutdownNow();
    }

    static void silentRunnerLosesTheCase() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 1_500);
        pool.open("req_d", "bt_req_d");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ConsumerPool.Relay> waiting = ex.submit(() -> pool.run("req_d",
            new JSONObject().put("testName", "t"), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 1, "published");
        ConsumerPool.Lease lost = pool.lease("req_d", queue.slot(0), "vm-dead", 1000);
        check(lost != null, "dead VM leased it");
        waitFor(() -> queue.published.size() == 2, "replacement message after the lease timed out");
        ConsumerPool.Lease again = pool.lease("req_d", queue.slot(1), "vm-alive", 2000);
        check(again != null && !again.jobId.equals(lost.jobId), "another VM leases it under a new id");
        check(!pool.complete(lost.jobId, new ConsumerPool.Relay(200, null, "{}".getBytes())), "late result from the silent VM refused");
        check(pool.heartbeat(lost.jobId), "silent VM told to stop");
        pool.complete(again.jobId, new ConsumerPool.Relay(200, null, "{\"status\":\"pass\"}".getBytes()));
        check(waiting.get(5, TimeUnit.SECONDS).status == 200, "case finishes on the second VM");
        pool.close("req_d");
        ex.shutdownNow();
    }

    static void cancelWithdrawsLeasedCase() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 60_000);
        pool.open("req_e", "bt_req_e");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ConsumerPool.Relay> waiting = ex.submit(() -> pool.run("req_e", new JSONObject(), 60_000, cancelled::get, LOG));
        waitFor(() -> queue.published.size() == 1, "published");
        ConsumerPool.Lease lease = pool.lease("req_e", queue.slot(0), "vm", 1000);
        cancelled.set(true);
        try {
            waiting.get(5, TimeUnit.SECONDS);
            check(false, "cancel should fail the wait");
        } catch (java.util.concurrent.ExecutionException e) {
            check(e.getCause() instanceof CancellationException, "waiting thread sees the cancel");
        }
        check(pool.heartbeat(lease.jobId), "runner told to stop");
        check(!pool.complete(lease.jobId, new ConsumerPool.Relay(200, null, new byte[0])), "result after cancel refused");
        pool.close("req_e");
        ex.shutdownNow();
    }

    static void pickupTimeout() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 1_200, 60_000);
        pool.open("req_f", "bt_req_f");
        try {
            pool.run("req_f", new JSONObject(), 60_000, () -> false, LOG);
            check(false, "nobody took the case: should time out");
        } catch (IOException e) {
            check(e.getMessage().contains("No QA consumer VM took the case"), "pickup timeout: " + e.getMessage());
        }
        pool.close("req_f");
    }

    static void publishFailureFailsTheCase() throws Exception {
        FakeQueue queue = new FakeQueue();
        queue.fail = true;
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 60_000);
        pool.open("req_g", "bt_req_g");
        try {
            pool.run("req_g", new JSONObject(), 60_000, () -> false, LOG);
            check(false, "publish failure should fail the case");
        } catch (IOException e) {
            check(e.getMessage().contains("Could not queue"), "broker down: " + e.getMessage());
        }
        pool.close("req_g");
    }

    static void closePurgesUnclaimedMessages() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 2, 60_000, 60_000);
        pool.open("req_h", "bt_req_h");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ConsumerPool.Relay> waiting = ex.submit(() -> pool.run("req_h", new JSONObject(), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 1, "published");
        pool.close("req_h");
        try {
            waiting.get(5, TimeUnit.SECONDS);
            check(false, "close should end the wait");
        } catch (java.util.concurrent.ExecutionException e) {
            check(e.getCause() instanceof CancellationException, "waiting case ends with the request");
        }
        waitFor(() -> queue.purged.contains("req_h"), "unclaimed messages purged");
        ex.shutdownNow();
    }

    static void runnerDiesBetweenCases() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 1_500);
        pool.open("req_i", "bt_req_i");
        ExecutorService ex = Executors.newFixedThreadPool(2);
        Future<ConsumerPool.Relay> first = ex.submit(() -> pool.run("req_i", new JSONObject().put("testName", "a"), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 1, "one slot for the first case");
        ConsumerPool.Lease lease = pool.lease("req_i", queue.slot(0), "vm1", 1000);
        pool.complete(lease.jobId, new ConsumerPool.Relay(200, null, "{}".getBytes()));
        first.get(5, TimeUnit.SECONDS);
        // The runner dies before asking for the next case; the second case must not wait for it
        Future<ConsumerPool.Relay> second = ex.submit(() -> pool.run("req_i", new JSONObject().put("testName", "b"), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 2, "silent runner's slot replaced");
        ConsumerPool.Lease next = pool.lease("req_i", queue.slot(1), "vm2", 1000);
        check(next != null && "b".equals(next.testRequest.getString("testName")), "new VM takes the waiting case");
        check(pool.lease("req_i", queue.slot(0), "vm1", 100) == null, "retired slot gets nothing");
        pool.complete(next.jobId, new ConsumerPool.Relay(200, null, "{}".getBytes()));
        second.get(5, TimeUnit.SECONDS);
        pool.close("req_i");
        ex.shutdownNow();
    }

    static void messageTakenButRunnerNeverChecksIn() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 1_200);
        pool.setQueueCheckIntervalMs(200);
        pool.open("req_j", "bt_req_j");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ConsumerPool.Relay> waiting = ex.submit(() -> pool.run("req_j", new JSONObject(), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 1, "published");
        Thread.sleep(1500);
        check(queue.published.size() == 1, "a message still in the queue is left alone");
        queue.taken.add(queue.slot(0));  // a VM took it, but its script never reached the Builder
        waitFor(() -> queue.published.size() == 2, "lost message replaced");
        ConsumerPool.Lease lease = pool.lease("req_j", queue.slot(1), "vm", 1000);
        check(lease != null, "replacement message's runner gets the case");
        pool.complete(lease.jobId, new ConsumerPool.Relay(200, null, "{}".getBytes()));
        waiting.get(5, TimeUnit.SECONDS);
        pool.close("req_j");
        ex.shutdownNow();
    }

    static void lostMessagesFailTheRequest() throws Exception {
        FakeQueue queue = new FakeQueue();
        queue.allTaken = true;  // every message is taken and nothing ever checks in
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 300);
        pool.setQueueCheckIntervalMs(100);
        pool.open("req_k", "bt_req_k");
        try {
            pool.run("req_k", new JSONObject(), 60_000, () -> false, LOG);
            check(false, "should give up");
        } catch (IOException e) {
            check(e.getMessage().contains("no runner ran a case"), "gives up with a diagnosis: " + e.getMessage());
        }
        check(queue.published.size() <= 6, "bounded republishing: " + queue.published.size());
        pool.close("req_k");
    }

    /**
     * Runners that claim their message and die before their first case (no Java 8 on the VM, a
     * failed jar download) count like lost messages: the waiting cases fail with the diagnosis
     * soon, instead of a replacement message every lease timeout until the 24h pickup timeout.
     */
    static void runnersDyingBeforeFirstCaseFailTheRequest() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 300);
        pool.open("req_n", "bt_req_n");
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread claimer = new Thread(() -> {
            int claimed = 0;
            while (!stop.get()) {
                if (queue.published.size() > claimed) {
                    pool.claim("req_n", queue.slot(claimed++));  // checks in, then dies before leasing
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        claimer.start();
        try {
            pool.run("req_n", new JSONObject(), 60_000, () -> false, LOG);
            check(false, "should give up");
        } catch (IOException e) {
            check(e.getMessage().contains("no runner ran a case"), "runners dying at start fail the request: " + e.getMessage());
        } finally {
            stop.set(true);
            claimer.join(2000);
        }
        check(queue.published.size() <= 6, "bounded republishing: " + queue.published.size());
        pool.close("req_n");
    }

    static void heartbeatAndResultAfterRequeueRefused() throws Exception {
        FakeQueue queue = new FakeQueue();
        ConsumerPool pool = new ConsumerPool(queue, "http://b:1", 1, 60_000, 1_000);
        pool.open("req_l", "bt_req_l");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ConsumerPool.Relay> waiting = ex.submit(() -> pool.run("req_l", new JSONObject(), 60_000, () -> false, LOG));
        waitFor(() -> queue.published.size() == 1, "published");
        ConsumerPool.Lease first = pool.lease("req_l", queue.slot(0), "vm1", 1000);
        check(pool.isExpected(first.jobId), "leased job expects a result");
        waitFor(() -> queue.published.size() == 2, "silent VM replaced");
        check(!pool.isExpected(first.jobId), "old job id no longer expected");
        check(pool.heartbeat(first.jobId), "old runner told to stop");
        check(pool.lease("req_l", queue.slot(0), "vm1", 100) == null, "old runner gets no more cases");
        ConsumerPool.Lease second = pool.lease("req_l", queue.slot(1), "vm2", 1000);
        check(!pool.heartbeat(second.jobId), "new runner keeps going");
        pool.complete(second.jobId, new ConsumerPool.Relay(200, null, "{}".getBytes()));
        waiting.get(5, TimeUnit.SECONDS);
        pool.close("req_l");
        ex.shutdownNow();
    }

    static void multipartRoundTrip() throws Exception {
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("vm_abc1234_case_1a2b.log", "로그 line\nNOK\n".getBytes(StandardCharsets.UTF_8));
        byte[] body = invokeMultipart("bnd", "{\"status\":\"fail\",\"message\":\"한글\"}".getBytes(StandardCharsets.UTF_8), files);
        String text = new String(body, StandardCharsets.UTF_8);
        check(text.startsWith("--bnd\r\n") && text.endsWith("--bnd--\r\n"), "multipart framing");
        check(text.contains("name=\"response\"") && text.contains("filename=\"vm_abc1234_case_1a2b.log\""), "parts named");
        check(text.contains("한글") && text.contains("로그 line"), "UTF-8 kept");
    }

    private static byte[] invokeMultipart(String boundary, byte[] response, java.util.Map<String, byte[]> files) throws Exception {
        java.lang.reflect.Method m = ConsumerRunner.class.getDeclaredMethod("multipart", String.class, byte[].class, java.util.Map.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, boundary, response, files);
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    private static void waitFor(Condition condition, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) {
                System.out.println("  ok " + name);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out: " + name);
    }

    private static void check(boolean ok, String name) {
        if (!ok) {
            throw new AssertionError("FAILED: " + name);
        }
        System.out.println("  ok " + name);
    }
}
