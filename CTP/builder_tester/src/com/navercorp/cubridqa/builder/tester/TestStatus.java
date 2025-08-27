package com.navercorp.cubridqa.builder.tester;

public enum TestStatus {
    PASS("pass"),
    FAIL("fail"),
    EXECUTION_ERROR("execution_error"),
    ENVIRONMENT_ERROR("environment_error"),
    BUILD_ERROR("build_error"),
    STARTED("started");
    
    private final String value;
    
    TestStatus(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    @Override
    public String toString() {
        return value;
    }
}