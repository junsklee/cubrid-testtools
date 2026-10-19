package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/** Turns a SQL case run into a TestResult; shared by the Docker and VM SQL executors. */
final class SqlResultWriter {
    private SqlResultWriter() {}

    /**
     * The result of one SQL case attempt: verdict plus the attempt log and artifacts under the
     * request's tests/ directory, named <prefix>_<commit>_<case>[...] (prefix "sql" in Docker,
     * "vm_sql" on a VM, which also tells the report which mode ran).
     */
    static TestResult.Builder build(TestRequest request, Path caseFile, SqlCaseRun finalRun, SqlCaseRun warmRun,
                                    int attemptNumber, Path testsDir, String prefix, int caseTimeoutSec,
                                    TestExecutionMetrics.Builder metricsBuilder, Logger testLogger) {
        TestResult.Builder resultBuilder = TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .timestamp(System.currentTimeMillis());

        String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
        String suffix = attemptNumber == 1 ? "" : "." + attemptNumber;

        if (testsDir != null) {
            // Primary attempt log: container/exec console + ConsoleAgent console output
            try {
                StringBuilder combined = new StringBuilder();
                if (finalRun.containerConsole != null) {
                    combined.append(finalRun.containerConsole);
                }
                Path caseConsole = finalRun.attemptDir != null ? finalRun.attemptDir.resolve("console.log") : null;
                if (caseConsole != null && Files.isReadable(caseConsole)) {
                    combined.append("\n----- ConsoleAgent console -----\n");
                    combined.append(new String(Files.readAllBytes(caseConsole), "UTF-8"));
                }
                Path logFile = testsDir.resolve(prefix + "_" + commitShort + "_" + safeTestName + suffix + ".log");
                Files.write(logFile, combined.toString().getBytes("UTF-8"));
                resultBuilder.addAttemptLogFile(logFile);
                try {
                    metricsBuilder.logSizeBytes(Files.size(logFile));
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                testLogger.warning("Failed to persist SQL attempt log: " + e.getMessage());
            }

            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "answer.diff",
                prefix + "_diff_" + commitShort + "_" + safeTestName + suffix + ".diff",
                "answer_diff", attemptNumber, finalRun.executionEnv, testLogger);
            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "actual.result",
                prefix + "_actual_" + commitShort + "_" + safeTestName + suffix + ".result",
                "actual_result", attemptNumber, finalRun.executionEnv, testLogger);
            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "expected.answer",
                prefix + "_expected_" + commitShort + "_" + safeTestName + suffix + ".answer",
                "expected_answer", attemptNumber, finalRun.executionEnv, testLogger);
            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "core.list",
                prefix + "_core_" + commitShort + "_" + safeTestName + suffix + ".txt",
                "core_list", attemptNumber, finalRun.executionEnv, testLogger);

            // Case source (once, on the first attempt). Custom ad-hoc cases have no
            // repo file, so write the source from the request; normal cases copy the file.
            if (attemptNumber == 1) {
                try {
                    Path caseCopy = testsDir.resolve(prefix + "_case_" + commitShort + "_" + safeTestName + ".sql");
                    if (request.hasCustomSqlScript()) {
                        Files.write(caseCopy, request.getCustomSqlScript().getBytes("UTF-8"));
                    } else if (caseFile != null && !Files.exists(caseCopy)) {
                        Files.copy(caseFile, caseCopy, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (Files.exists(caseCopy)) {
                        resultBuilder.addArtifactFile(caseCopy, "case_source", attemptNumber, finalRun.executionEnv);
                    }
                } catch (Exception e) {
                    testLogger.warning("Failed to persist SQL case source: " + e.getMessage());
                }
            }

            // Warm-agent run that was overruled by fresh-verify: keep its console for the report
            if (warmRun != null) {
                try {
                    StringBuilder warmLog = new StringBuilder();
                    warmLog.append("Warm-agent run outcome: ").append(describe(warmRun)).append("\n\n");
                    if (warmRun.containerConsole != null) {
                        warmLog.append(warmRun.containerConsole);
                    }
                    Path warmConsole = warmRun.attemptDir != null ? warmRun.attemptDir.resolve("console.log") : null;
                    if (warmConsole != null && Files.isReadable(warmConsole)) {
                        warmLog.append("\n----- ConsoleAgent console (warm agent) -----\n");
                        warmLog.append(new String(Files.readAllBytes(warmConsole), "UTF-8"));
                    }
                    Path warmFile = testsDir.resolve(prefix + "_warm_" + commitShort + "_" + safeTestName + suffix + ".log");
                    Files.write(warmFile, warmLog.toString().getBytes("UTF-8"));
                    resultBuilder.addArtifactFile(warmFile, "warm_console", attemptNumber, "warm_agent");
                } catch (Exception e) {
                    testLogger.warning("Failed to persist warm-agent SQL log: " + e.getMessage());
                }
            }
        }

        SqlResultParser.SqlCaseOutcome outcome = finalRun.outcome;
        if (outcome == null) {
            resultBuilder.status(finalRun.failureMessage != null && finalRun.failureMessage.contains("timeout")
                    ? TestStatus.EXECUTION_ERROR : TestStatus.ENVIRONMENT_ERROR)
                .message(finalRun.failureMessage != null ? finalRun.failureMessage : "SQL case produced no outcome");
        } else if (outcome.isPass()) {
            resultBuilder.status(TestStatus.PASS);
            if (warmRun != null && warmRun.outcome != null && warmRun.outcome.isFail()) {
                resultBuilder.message("Warm-agent failure not reproduced in fresh container (suspected cross-case contamination)");
            }
        } else if (outcome.isFail()) {
            String message = "Answer mismatch (see answer diff)";
            if (outcome.core) {
                message += "; core file produced";
            }
            resultBuilder.status(TestStatus.FAIL).message(message);
        } else if (SqlResultParser.STATUS_NOTRUN.equals(outcome.status)) {
            String caseName = caseFile != null ? caseFile.getFileName().toString() : (safeTestName + ".sql");
            resultBuilder.status(TestStatus.EXECUTION_ERROR)
                .message("No answer file for case (expected .../answers/"
                    + caseName.replace(".sql", ".answer") + ")");
        } else if (SqlResultParser.STATUS_TIMEOUT.equals(outcome.status)) {
            resultBuilder.status(TestStatus.EXECUTION_ERROR)
                .message("Case timed out after " + caseTimeoutSec + "s");
        } else {
            resultBuilder.status(TestStatus.EXECUTION_ERROR)
                .message("SQL case execution error" + (outcome.reason != null ? ": " + outcome.reason : ""));
        }

        return resultBuilder;
    }

    private static void persistArtifact(TestResult.Builder resultBuilder, Path testsDir, Path attemptDir,
                                 String sourceName, String targetName, String artifactType,
                                 int attempt, String executionEnv, Logger testLogger) {
        if (attemptDir == null) {
            return;
        }
        Path source = attemptDir.resolve(sourceName);
        if (!Files.isReadable(source)) {
            return;
        }
        try {
            Path target = testsDir.resolve(targetName);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            resultBuilder.addArtifactFile(target, artifactType, attempt, executionEnv);
        } catch (Exception e) {
            testLogger.warning("Failed to persist SQL artifact " + sourceName + ": " + e.getMessage());
        }
    }


    static boolean needsFreshVerify(SqlCaseRun warmRun) {
        if (warmRun.outcome == null) {
            return true;
        }
        return !warmRun.outcome.isPass() && !SqlResultParser.STATUS_NOTRUN.equals(warmRun.outcome.status);
    }

    static String describe(SqlCaseRun run) {
        if (run.outcome != null) {
            return "status=" + run.outcome.status + (run.outcome.core ? ", core" : "");
        }
        return run.failureMessage != null ? run.failureMessage : "no outcome";
    }
}
