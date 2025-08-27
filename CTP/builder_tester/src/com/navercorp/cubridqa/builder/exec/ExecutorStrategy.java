package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import java.nio.file.Path;
import java.util.logging.Logger;

public interface ExecutorStrategy {
    TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception;
}