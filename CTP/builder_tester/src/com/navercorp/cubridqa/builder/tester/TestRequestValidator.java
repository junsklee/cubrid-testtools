package com.navercorp.cubridqa.builder.tester;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates test execution parameters (runMode, minRuns, maxRuns, timeBudgetMs).
 * Rejects malformed or semantically invalid requests.
 */
public class TestRequestValidator {

    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private String runMode;
        private int minRuns;
        private int maxRuns;
        private Long timeBudgetMs;

        public void addError(String error) {
            this.errors.add(error);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }

        public String getRunMode() { return runMode; }
        public void setRunMode(String runMode) { this.runMode = runMode; }

        public int getMinRuns() { return minRuns; }
        public void setMinRuns(int minRuns) { this.minRuns = minRuns; }

        public int getMaxRuns() { return maxRuns; }
        public void setMaxRuns(int maxRuns) { this.maxRuns = maxRuns; }

        public Long getTimeBudgetMs() { return timeBudgetMs; }
        public void setTimeBudgetMs(Long timeBudgetMs) { this.timeBudgetMs = timeBudgetMs; }
    }

    /**
     * Strictly validates the run parameters in the request JSON.
     */
    public static ValidationResult validate(JSONObject json) {
        ValidationResult result = new ValidationResult();

        // 1. Validate runMode
        String runMode = "until-pass";
        if (json.has("runMode")) {
            Object val = json.get("runMode");
            if (!(val instanceof String)) {
                result.addError("Field 'runMode' must be a String.");
            } else {
                runMode = ((String) val).toLowerCase();
                if (!runMode.equals("until-pass") && !runMode.equals("until-fail") && !runMode.equals("fixed-runs")) {
                    result.addError("Invalid 'runMode': " + runMode + ". Must be one of [until-pass, until-fail, fixed-runs].");
                }
            }
        }
        result.setRunMode(runMode);

        // 2. Validate minRuns
        int minRuns = 1;
        if (json.has("minRuns")) {
            Object val = json.get("minRuns");
            if (!(val instanceof Integer)) {
                result.addError("Field 'minRuns' must be an Integer (received " + (val == null ? "null" : val.getClass().getSimpleName()) + ").");
            } else {
                minRuns = (Integer) val;
                if (minRuns < 1) {
                    result.addError("Field 'minRuns' must be >= 1.");
                }
            }
        }
        result.setMinRuns(minRuns);

        // 3. Validate maxRuns
        int maxRuns = minRuns;
        if (json.has("maxRuns")) {
            Object val = json.get("maxRuns");
            if (!(val instanceof Integer)) {
                result.addError("Field 'maxRuns' must be an Integer (received " + (val == null ? "null" : val.getClass().getSimpleName()) + ").");
            } else {
                maxRuns = (Integer) val;
                if (maxRuns < 1) {
                    result.addError("Field 'maxRuns' must be >= 1.");
                }
            }
        } else if (json.has("minRuns")) {
            // If minRuns was provided but maxRuns wasn't, default maxRuns to minRuns
            maxRuns = minRuns;
        }
        result.setMaxRuns(maxRuns);

        // 4. Validate timeBudgetMs
        if (json.has("timeBudgetMs")) {
            Object val = json.get("timeBudgetMs");
            if (!(val instanceof Integer) && !(val instanceof Long)) {
                result.addError("Field 'timeBudgetMs' must be an Integer or Long.");
            } else {
                long tb = ((Number) val).longValue();
                if (tb < 1) {
                    result.addError("Field 'timeBudgetMs' must be >= 1.");
                }
                result.setTimeBudgetMs(tb);
            }
        }

        // 5. Semantic checks (Cross-field validation)
        if (result.isValid()) {
            if (result.getMinRuns() > result.getMaxRuns()) {
                result.addError("minRuns (" + result.getMinRuns() + ") cannot be greater than maxRuns (" + result.getMaxRuns() + ").");
            }

            if ("fixed-runs".equals(result.getRunMode())) {
                if (result.getMinRuns() != result.getMaxRuns()) {
                    result.addError("In 'fixed-runs' mode, minRuns (" + result.getMinRuns() + ") must equal maxRuns (" + result.getMaxRuns() + ").");
                }
                if (result.getTimeBudgetMs() != null) {
                    result.addError("'fixed-runs' mode does not support 'timeBudgetMs' stop condition.");
                }
            }
        }

        return result;
    }
}
