package com.navercorp.cubridqa.builder.tester;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Work to do when a request ends on this tester (finalize or cancel), for executors that keep
 * state across a request's cases, such as the VM SQL executor's running test DB.
 */
public final class RequestEndHooks {
    private static final Logger logger = Logger.getLogger(RequestEndHooks.class.getName());

    /** A hook; cancelled is true when the request was cancelled rather than finished. */
    public interface Hook {
        void requestEnded(String requestId, boolean cancelled);
    }

    private static final List<Hook> HOOKS = new CopyOnWriteArrayList<>();

    private RequestEndHooks() {}

    public static void register(Hook hook) {
        HOOKS.add(hook);
    }

    /**
     * Runs the hooks. A finalize waits for them (a consumer runner exits right after it); a cancel
     * does not, since the case still running stops on its own first.
     */
    public static void requestEnded(String requestId, boolean cancelled) {
        for (Hook hook : HOOKS) {
            Runnable run = () -> {
                try {
                    hook.requestEnded(requestId, cancelled);
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Request-end hook failed for " + requestId, e);
                }
            };
            if (cancelled) {
                Thread t = new Thread(run, "request-end-" + requestId);
                t.setDaemon(true);
                t.start();
            } else {
                run.run();
            }
        }
    }
}
