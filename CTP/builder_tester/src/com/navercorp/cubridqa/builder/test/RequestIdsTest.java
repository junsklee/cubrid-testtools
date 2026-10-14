package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.logging.RequestIds;
import com.navercorp.cubridqa.builder.tester.SafeIo;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Request IDs from unauthenticated endpoints must never resolve outside their base directory,
 * and privileged deletion must refuse non-normalized or shallow paths.
 */
public class RequestIdsTest {

    public static void main(String[] args) {
        System.out.println("=== RequestIdsTest Suite ===\n");

        for (String ok : new String[] {"req_20261015_101500_ab12", "adhoc_1", "client-run.7", "A"}) {
            check(RequestIds.isValid(ok), "valid: " + ok);
        }
        for (String bad : new String[] {null, "", ".", "..", "../../etc", "a/b", "a\\b", "req_1/..",
                "x..y", ".hidden", "-rf", "req_x'\"><img src=x>", "req 1", "a\u0000b"}) {
            check(!RequestIds.isValid(bad), "invalid: " + bad);
        }
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 129; i++) longId.append('a');
        check(!RequestIds.isValid(longId.toString()), "invalid: 129 chars");

        Path base = Paths.get("/tmp/bt/log/requests");
        check(RequestIds.resolveUnder(base, "req_1").equals(Paths.get("/tmp/bt/log/requests/req_1")), "resolveUnder child");
        expectReject(() -> RequestIds.resolveUnder(base, "../../../../etc"), "resolveUnder traversal");
        expectReject(() -> RequestIds.resolveUnder(base, ".."), "resolveUnder ..");

        check(SafeIo.isSafeDeletionTarget(Paths.get("/home/qa/tmp/test_1")), "safe: normalized deep path");
        check(!SafeIo.isSafeDeletionTarget(Paths.get("/home/qa/log/requests/../../../../etc")), "unsafe: dot-dot");
        check(!SafeIo.isSafeDeletionTarget(Paths.get("/etc")), "unsafe: shallow");
        check(!SafeIo.isSafeDeletionTarget(Paths.get("/home/qa")), "unsafe: home dir");

        System.out.println("\n=== All RequestIdsTest tests passed! ===");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
        System.out.println("  ok  " + label);
    }

    private static void expectReject(Runnable r, String label) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            System.out.println("  ok  " + label);
            return;
        }
        throw new AssertionError("FAILED (accepted): " + label);
    }
}
