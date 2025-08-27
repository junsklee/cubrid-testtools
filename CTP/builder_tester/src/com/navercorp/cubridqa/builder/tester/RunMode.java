package com.navercorp.cubridqa.builder.tester;

public enum RunMode {
    UNTIL_PASS("until-pass"),
    UNTIL_FAIL("until-fail"),
    FIXED_RUNS("fixed-runs");
    
    private final String value;
    
    RunMode(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static RunMode fromString(String value) {
        if (value == null) {
            return UNTIL_PASS;
        }
        
        String normalized = value.toLowerCase();
        for (RunMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return UNTIL_PASS;
    }
}