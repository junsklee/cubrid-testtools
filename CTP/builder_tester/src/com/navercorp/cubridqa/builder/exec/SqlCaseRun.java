package com.navercorp.cubridqa.builder.exec;

import java.nio.file.Path;

/** Everything one SQL case execution produced, host-side (Docker container or VM). */
class SqlCaseRun {
    SqlResultParser.SqlCaseOutcome outcome;
    Path attemptDir;        // dir with status.line/console.log/expected/actual/diff
    String containerConsole; // docker run / docker exec / VM runner stdout
    String executionEnv;    // "warm_agent", "fresh_container", "vm_warm" or "vm_fresh"
    String failureMessage;  // set when the run could not produce an outcome
}
