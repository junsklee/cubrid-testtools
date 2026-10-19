package com.navercorp.cubridqa.builder.consumer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.navercorp.cubridqa.builder.BuilderConfig;

/**
 * Hands the cases of "executionMode": "consumer" requests to QAHome consumer VMs.
 *
 * <p>The Builder cannot push cases to those VMs: they run regression from the QAHome queues and
 * only take a Builder-Tester job while idle. So a case waits here, a message on consumer_queue
 * makes an idle consumer run common/ext/run_builder_tester.sh, and that runner leases cases over
 * HTTP, runs each with the VM-mode tester and posts the tester's /test response back.
 * {@link #run} returns that response, which BuilderTask parses as if it had called the tester.
 *
 * <p>Each message is a slot: one VM working on the request. A request has at most
 * consumer_max_parallel live slots; a runner keeps leasing until no case is left. A slot dies
 * when its runner stops checking in (heartbeats, leases) for consumer_lease_timeout_seconds,
 * or when its message left the queue and no runner ever checked in for it; a dead slot's case
 * goes back to the queue and a new message replaces the slot.
 */
public class ConsumerPool {
    private static final Logger logger = Logger.getLogger(ConsumerPool.class.getName());
    /** A case handed to VMs that went silent this many times fails instead of trying again. */
    static final int MAX_LEASES = 3;

    private static volatile ConsumerPool instance;

    /** The consumer queue; ConsumerQueue in production. */
    public interface Publisher {
        void publish(Map<String, String> properties, String text) throws Exception;

        void purge(String requestId) throws Exception;

        /** BT_SLOT of the request's messages still in the queue. */
        Set<String> queuedSlots(String requestId) throws Exception;
    }

    /** What the runner relayed: the VM tester's HTTP status, content type and body. */
    public static final class Relay {
        public final int status;
        public final String contentType;
        public final byte[] body;

        public Relay(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    /** A leased case, as the runner gets it. */
    public static final class Lease {
        public final String jobId;
        public final JSONObject testRequest;

        Lease(String jobId, JSONObject testRequest) {
            this.jobId = jobId;
            this.testRequest = testRequest;
        }
    }

    private enum SlotState { QUEUED, CLAIMED, DONE }

    private static final class Slot {
        final String id;
        volatile SlotState state = SlotState.QUEUED;
        volatile long lastSeen = System.currentTimeMillis();
        /** When a QUEUED slot was first found missing from the queue; 0 while it is there. */
        volatile long missingSince;

        Slot(String id) {
            this.id = id;
        }
    }

    private static final class Job {
        final String requestId;
        final JSONObject testRequest;
        final CompletableFuture<Relay> result = new CompletableFuture<>();
        volatile String jobId = UUID.randomUUID().toString();
        volatile Slot slot;
        volatile String runner;
        volatile long waitingSince = System.currentTimeMillis();
        volatile long leasedAt;
        volatile long lastHeartbeat;
        /** Withdrawn (cancel, timeout) or finished: no lease, heartbeat or result applies any more. */
        volatile boolean closed;
        int leases;

        Job(String requestId, JSONObject testRequest) {
            this.requestId = requestId;
            this.testRequest = testRequest;
        }
    }

    private static final class RequestState {
        final String requestId;
        final String buildLabel;
        final LinkedBlockingDeque<Job> pending = new LinkedBlockingDeque<>();
        final Set<Job> leased = ConcurrentHashMap.newKeySet();
        final Map<String, Slot> slots = new ConcurrentHashMap<>();
        volatile boolean closed;
        long lastReap;
        long lastQueueCheck;
        int lostMessages;
        /** Set when VMs keep taking messages without a runner checking in: waiting cases fail. */
        volatile String failure;

        RequestState(String requestId, String buildLabel) {
            this.requestId = requestId;
            this.buildLabel = buildLabel;
        }

        int liveSlots() {
            int live = 0;
            for (Slot slot : slots.values()) {
                if (slot.state != SlotState.DONE) {
                    live++;
                }
            }
            return live;
        }

        int count(SlotState state) {
            int n = 0;
            for (Slot slot : slots.values()) {
                if (slot.state == state) {
                    n++;
                }
            }
            return n;
        }
    }

    private final Publisher publisher;
    private final String builderUrl;
    private final int maxParallel;
    private final long pickupTimeoutMs;
    private final long leaseTimeoutMs;
    private final Map<String, RequestState> requests = new ConcurrentHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** How often waiting requests look for their messages in the queue. */
    private volatile long queueCheckIntervalMs = TimeUnit.SECONDS.toMillis(60);

    public ConsumerPool(Publisher publisher, String builderUrl, int maxParallel, long pickupTimeoutMs, long leaseTimeoutMs) {
        this.publisher = publisher;
        this.builderUrl = builderUrl;
        this.maxParallel = Math.max(1, maxParallel);
        this.pickupTimeoutMs = pickupTimeoutMs;
        this.leaseTimeoutMs = leaseTimeoutMs;
    }

    /** Creates the pool when consumer_activemq_url is set. builderUrl is how VMs reach this Builder. */
    public static synchronized void initialize(BuilderConfig config, String builderUrl) {
        if (instance != null || !config.isConsumerQueueEnabled()) {
            return;
        }
        final ConsumerQueue queue = new ConsumerQueue(config.getConsumerActiveMqUrl(), config.getConsumerActiveMqUser(),
            config.getConsumerActiveMqPassword(), config.getConsumerQueue());
        final int priority = config.getConsumerMessagePriority();
        // An unclaimed message outlives the pickup timeout by an hour at most
        final long timeToLive = config.getConsumerPickupTimeoutMillis() + TimeUnit.HOURS.toMillis(1);
        Publisher publisher = new Publisher() {
            @Override
            public void publish(Map<String, String> properties, String text) throws Exception {
                queue.publish(properties, text, priority, timeToLive);
            }

            @Override
            public void purge(String requestId) throws Exception {
                queue.purge("BT_REQUEST_ID", requestId);
            }

            @Override
            public Set<String> queuedSlots(String requestId) throws Exception {
                return queue.browse("BT_REQUEST_ID", requestId, "BT_SLOT");
            }
        };
        instance = new ConsumerPool(publisher, builderUrl, config.getConsumerMaxParallel(),
            config.getConsumerPickupTimeoutMillis(), config.getConsumerLeaseTimeoutMillis());
        logger.info("Consumer queue enabled: " + config.getConsumerQueue() + " on " + config.getConsumerActiveMqUrl()
            + ", runners reach this Builder at " + builderUrl);
    }

    /** The pool, or null when the consumer queue is not configured. */
    public static ConsumerPool get() {
        return instance;
    }

    public String getBuilderUrl() {
        return builderUrl;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    /** For tests: how often waiting requests look for their messages in the queue. */
    public void setQueueCheckIntervalMs(long intervalMs) {
        this.queueCheckIntervalMs = intervalMs;
    }

    /** Starts accepting cases of a request. buildLabel names the build in the messages (one word). */
    public void open(String requestId, String buildLabel) {
        requests.putIfAbsent(requestId, new RequestState(requestId, buildLabel));
    }

    /**
     * Ends a request: cases still waiting fail as cancelled, running runners are told to stop at
     * their next heartbeat or lease, and messages no consumer took are removed from the queue.
     */
    public void close(String requestId) {
        RequestState state = requests.remove(requestId);
        if (state == null) {
            return;
        }
        state.closed = true;
        Job job;
        while ((job = state.pending.poll()) != null) {
            synchronized (job) {
                job.closed = true;
            }
            jobs.remove(job.jobId);
            job.result.completeExceptionally(new CancellationException("request finished"));
        }
        if (state.count(SlotState.QUEUED) > 0) {
            Thread purge = new Thread(() -> {
                try {
                    publisher.purge(requestId);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Could not remove the unclaimed queue messages of " + requestId, e);
                }
            }, "consumer-purge-" + requestId);
            purge.setDaemon(true);
            purge.start();
        }
    }

    /**
     * Queues one case and waits for a consumer VM to run it.
     *
     * @param runTimeoutMs how long a leased case may run before it fails
     * @param cancelled    polled every second; when true the case is withdrawn
     * @throws CancellationException when cancelled or the request was closed
     * @throws IOException           when no VM took the case in time or VMs kept going silent
     */
    public Relay run(String requestId, JSONObject testRequest, long runTimeoutMs, BooleanSupplier cancelled, Logger log)
            throws Exception {
        RequestState state = requests.get(requestId);
        if (state == null || state.closed) {
            throw new CancellationException("request " + requestId + " is not open for consumer execution");
        }
        Job job = new Job(requestId, testRequest);
        jobs.put(job.jobId, job);
        state.pending.add(job);
        try {
            ensureSlots(state, log);
            while (true) {
                try {
                    return job.result.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException ignore) {
                    // check the clocks below
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw cause instanceof Exception ? (Exception) cause : new IOException(cause);
                }
                long now = System.currentTimeMillis();
                boolean requeued = false;
                synchronized (job) {
                    if (job.result.isDone()) {
                        continue;
                    }
                    if (cancelled.getAsBoolean() || state.closed) {
                        job.closed = true;
                        state.pending.remove(job);
                        throw new CancellationException("cancelled");
                    }
                    if (job.slot == null) {
                        // Not leased (or a lease is being handed out right now: not in pending either)
                        String failure = state.failure;
                        if (failure != null && state.pending.remove(job)) {
                            job.closed = true;
                            throw new IOException(failure);
                        }
                        if (now - job.waitingSince > pickupTimeoutMs && state.pending.remove(job)) {
                            job.closed = true;
                            throw new IOException("No QA consumer VM took the case within "
                                + TimeUnit.MILLISECONDS.toMinutes(pickupTimeoutMs) + " minutes");
                        }
                    } else if (now - job.lastHeartbeat > leaseTimeoutMs) {
                        String lost = job.slot.id + " (" + job.runner + ")";
                        job.slot.state = SlotState.DONE;
                        state.leased.remove(job);
                        jobs.remove(job.jobId);
                        if (job.leases >= MAX_LEASES) {
                            job.closed = true;
                            throw new IOException("The consumer VM running the case stopped responding (" + job.leases
                                + " VMs in a row; last " + lost + ")");
                        }
                        log.warning("Consumer runner " + lost + " stopped sending heartbeats for "
                            + testRequest.optString("testName") + "; handing the case to another VM");
                        // A new id, so the silent VM's late heartbeat or result is refused
                        job.jobId = UUID.randomUUID().toString();
                        job.slot = null;
                        job.runner = null;
                        job.waitingSince = now;
                        jobs.put(job.jobId, job);
                        state.pending.addFirst(job);
                        requeued = true;
                    } else if (now - job.leasedAt > runTimeoutMs) {
                        job.closed = true;
                        throw new IOException("The case did not finish on consumer VM " + job.runner + " within "
                            + TimeUnit.MILLISECONDS.toMinutes(runTimeoutMs) + " minutes");
                    }
                }
                // Outside the job's lock: publishing talks to the broker
                if (reapSlots(state, now, log) || requeued) {
                    ensureSlots(state, log);
                }
            }
        } finally {
            synchronized (job) {
                job.closed = true;
            }
            state.pending.remove(job);
            state.leased.remove(job);
            // A runner whose case was withdrawn learns it from its next heartbeat: an unknown id
            // means "stop".
            jobs.remove(job.jobId);
        }
    }

    /**
     * Gives a runner its next case, waiting up to waitMs for one. Returns null when the runner
     * should exit: the request is finished or unknown, the slot is not one of its messages or is
     * no longer live, or no case came up in time.
     */
    public Lease lease(String requestId, String slotId, String runner, long waitMs) throws InterruptedException {
        RequestState state = requests.get(requestId);
        Slot slot = state == null || slotId == null ? null : state.slots.get(slotId);
        if (state == null || state.closed || slot == null || slot.state == SlotState.DONE) {
            return null;
        }
        touch(slot);
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            // In steps of a second, so a request that ends meanwhile lets its runners go at once
            Job job = left > 0 ? state.pending.poll(Math.min(left, 1000L), TimeUnit.MILLISECONDS) : null;
            if (job == null) {
                if (left > 0 && !state.closed && slot.state != SlotState.DONE) {
                    touch(slot);
                    continue;
                }
                // Decide under the request's lock, which ensureSlots also takes: a case queued
                // right now either is seen here or sees this slot gone and publishes a new one.
                synchronized (state) {
                    job = state.closed ? null : state.pending.poll();
                    if (job == null) {
                        slot.state = SlotState.DONE;
                        return null;
                    }
                }
            }
            if (state.closed || slot.state == SlotState.DONE) {
                state.pending.addFirst(job);
                return null;
            }
            synchronized (job) {
                if (job.closed || job.result.isDone()) {
                    continue;
                }
                long now = System.currentTimeMillis();
                job.slot = slot;
                job.runner = runner;
                job.leasedAt = now;
                job.lastHeartbeat = now;
                job.leases++;
                state.leased.add(job);
                touch(slot);
                return new Lease(job.jobId, job.testRequest);
            }
        }
    }

    /** Records a runner's heartbeat. Returns true when the runner should stop the case. */
    public boolean heartbeat(String jobId) {
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null) {
            return true;
        }
        synchronized (job) {
            if (job.closed || !jobId.equals(job.jobId) || job.slot == null) {
                return true;
            }
            job.lastHeartbeat = System.currentTimeMillis();
            touch(job.slot);
            return false;
        }
    }

    /** True when a result for this job would be accepted (checked before reading its body). */
    public boolean isExpected(String jobId) {
        Job job = jobId == null ? null : jobs.get(jobId);
        return job != null && !job.closed && jobId.equals(job.jobId) && job.slot != null;
    }

    /** Delivers a runner's result. Returns false when the case is no longer expected. */
    public boolean complete(String jobId, Relay relay) {
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null) {
            return false;
        }
        synchronized (job) {
            if (job.closed || !jobId.equals(job.jobId) || job.slot == null) {
                return false;
            }
            return job.result.complete(relay);
        }
    }

    /**
     * Called by a runner before it starts: true when the request is open and the slot is one of
     * its live messages, which the runner now claims.
     */
    public boolean claim(String requestId, String slotId) {
        RequestState state = requestId == null ? null : requests.get(requestId);
        Slot slot = state == null || slotId == null ? null : state.slots.get(slotId);
        if (state == null || state.closed || slot == null || slot.state == SlotState.DONE) {
            return false;
        }
        touch(slot);
        return true;
    }

    /** Snapshot for /health: open requests with their waiting and running cases, queued messages and runners. */
    public Map<String, int[]> snapshot() {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (RequestState state : requests.values()) {
            out.put(state.requestId, new int[] {state.pending.size(), state.leased.size(),
                state.count(SlotState.QUEUED), state.count(SlotState.CLAIMED)});
        }
        return out;
    }

    private static void touch(Slot slot) {
        slot.lastSeen = System.currentTimeMillis();
        if (slot.state == SlotState.QUEUED) {
            slot.state = SlotState.CLAIMED;
        }
    }

    /**
     * Retires slots that will not take cases any more. Returns true when one was retired, so the
     * caller publishes replacements.
     * <ul>
     * <li>A claimed slot whose runner has no case and has not checked in for the lease timeout
     * (the runner died between cases).</li>
     * <li>A queued slot whose message left the queue a lease timeout ago without a runner ever
     * checking in (the VM could not reach this Builder, had no script, the message expired).</li>
     * </ul>
     * Too many of the latter means the VMs cannot run the cases at all: waiting cases then fail
     * instead of waiting for the pickup timeout.
     */
    private boolean reapSlots(RequestState state, long now, Logger log) {
        boolean checkQueue;
        synchronized (state) {
            if (now - state.lastReap < 1000) {
                return false;
            }
            state.lastReap = now;
            checkQueue = state.count(SlotState.QUEUED) > 0 && now - state.lastQueueCheck >= queueCheckIntervalMs;
            if (checkQueue) {
                state.lastQueueCheck = now;
            }
        }
        Set<String> queued = null;
        if (checkQueue) {
            try {
                queued = publisher.queuedSlots(state.requestId);
            } catch (Exception e) {
                log.log(Level.FINE, "Could not look at the consumer queue", e);
            }
        }
        boolean retired = false;
        synchronized (state) {
            for (Slot slot : state.slots.values()) {
                if (slot.state == SlotState.CLAIMED && now - slot.lastSeen > leaseTimeoutMs && !holdsCase(state, slot)) {
                    slot.state = SlotState.DONE;
                    retired = true;
                    log.warning("Consumer runner of slot " + slot.id.substring(0, 8) + " stopped checking in; replacing it");
                } else if (slot.state == SlotState.QUEUED && queued != null) {
                    if (queued.contains(slot.id)) {
                        slot.missingSince = 0;
                    } else if (slot.missingSince == 0) {
                        slot.missingSince = now;
                    }
                }
                if (slot.state == SlotState.QUEUED && slot.missingSince > 0 && now - slot.missingSince > leaseTimeoutMs) {
                    slot.state = SlotState.DONE;
                    retired = true;
                    state.lostMessages++;
                    log.warning("A QA consumer VM took message " + slot.id.substring(0, 8) + " of request " + state.requestId
                        + " but no runner checked in from it; publishing another (can the VMs reach " + builderUrl
                        + " and run common/ext/run_builder_tester.sh?)");
                }
            }
            if (state.lostMessages > 2 * maxParallel + 2 && state.failure == null) {
                state.failure = "QA consumer VMs took " + state.lostMessages + " messages but no runner checked in: "
                    + "check that the VMs reach " + builderUrl + " and have common/ext/run_builder_tester.sh";
                log.severe(state.failure);
                return false;
            }
        }
        return retired;
    }

    private static boolean holdsCase(RequestState state, Slot slot) {
        for (Job job : state.leased) {
            if (job.slot == slot) {
                return true;
            }
        }
        return false;
    }

    /**
     * Publishes slot messages until every waiting or running case has a VM working on the
     * request, up to maxParallel. A failed publish with no live slot at all fails the case, since
     * nothing would ever take it.
     */
    private void ensureSlots(RequestState state, Logger log) throws IOException {
        synchronized (state) {
            if (state.closed || state.failure != null) {
                return;
            }
            int wanted = Math.min(maxParallel, state.pending.size() + state.leased.size());
            for (int have = state.liveSlots(); have < wanted; have++) {
                Slot slot = new Slot(UUID.randomUUID().toString().replace("-", ""));
                Map<String, String> properties = new LinkedHashMap<>();
                // What start_consumer.sh requires of every message (hasTestBuild), and MSG_ID,
                // which it records as the running job. None of these reaches QAHome's database:
                // the message never went through the QAHome sender.
                properties.put("MSG_ID", "bt_" + slot.id);
                properties.put("BUILD_ID", state.buildLabel);
                properties.put("BUILD_SVN_BRANCH", "builder_tester");
                properties.put("BUILD_SCENARIOS", "builder_tester");
                properties.put("BUILD_IS_FROM_GIT", "1");
                properties.put("BUILD_BIT", "64");
                properties.put("BT_BUILDER_URL", builderUrl);
                properties.put("BT_REQUEST_ID", state.requestId);
                properties.put("BT_SLOT", slot.id);
                state.slots.put(slot.id, slot);
                try {
                    publisher.publish(properties, "Builder-Tester request " + state.requestId);
                    log.info("Queued consumer message for request " + state.requestId + " (slot " + slot.id.substring(0, 8) + ")");
                } catch (Exception e) {
                    state.slots.remove(slot.id);
                    log.log(Level.WARNING, "Could not queue a consumer message for request " + state.requestId, e);
                    if (state.liveSlots() == 0) {
                        throw new IOException("Could not queue the case for the QA consumer VMs: " + e.getMessage(), e);
                    }
                    return;
                }
            }
        }
    }
}
