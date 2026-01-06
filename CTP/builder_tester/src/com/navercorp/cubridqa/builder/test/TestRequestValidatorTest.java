package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.tester.TestRequestValidator;
import com.navercorp.cubridqa.builder.tester.TestRequestValidator.ValidationResult;
import org.json.JSONObject;

/**
 * Unit tests for {@link TestRequestValidator}.
 */
public class TestRequestValidatorTest {

    public static void main(String[] args) {
        System.out.println("=== TestRequestValidatorTest Suite ===\n");

        testValidUntilPass();
        testValidFixedRuns();
        testInvalidRunMode();
        testMissingRequiredFields();
        testWrongTypes();
        testNegativeValues();
        testMinGreaterThanMax();
        testFixedRunsMismatch();
        testFixedRunsWithTimeBudget();

        System.out.println("\n=== All TestRequestValidatorTest tests passed! ===");
    }

    private static void testValidUntilPass() {
        System.out.println("Test: Valid until-pass request");
        JSONObject json = new JSONObject()
            .put("runMode", "until-pass")
            .put("minRuns", 1)
            .put("maxRuns", 3)
            .put("timeBudgetMs", 300000);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert result.isValid() : "Should be valid: " + result.getErrors();
        assert "until-pass".equals(result.getRunMode());
        assert result.getMinRuns() == 1;
        assert result.getMaxRuns() == 3;
        assert result.getTimeBudgetMs() == 300000L;
        System.out.println("  ✓ OK");
    }

    private static void testValidFixedRuns() {
        System.out.println("Test: Valid fixed-runs request");
        JSONObject json = new JSONObject()
            .put("runMode", "fixed-runs")
            .put("minRuns", 5)
            .put("maxRuns", 5);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert result.isValid() : "Should be valid: " + result.getErrors();
        assert "fixed-runs".equals(result.getRunMode());
        assert result.getMinRuns() == 5;
        assert result.getMaxRuns() == 5;
        assert result.getTimeBudgetMs() == null;
        System.out.println("  ✓ OK");
    }

    private static void testInvalidRunMode() {
        System.out.println("Test: Invalid runMode");
        JSONObject json = new JSONObject()
            .put("runMode", "invalid-mode")
            .put("minRuns", 1)
            .put("maxRuns", 1);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert !result.isValid() : "Should be invalid";
        assert result.getErrors().stream().anyMatch(e -> e.contains("Invalid 'runMode'"));
        System.out.println("  ✓ OK");
    }

    private static void testMissingRequiredFields() {
        System.out.println("Test: Missing run parameters (should use defaults)");
        JSONObject json = new JSONObject()
            .put("runMode", "until-pass");
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert result.isValid() : "Should be valid (use defaults): " + result.getErrors();
        assert result.getMinRuns() == 1;
        assert result.getMaxRuns() == 1;
        System.out.println("  ✓ OK");
    }

    private static void testWrongTypes() {
        System.out.println("Test: Wrong types");
        JSONObject json = new JSONObject()
            .put("runMode", 123)
            .put("minRuns", "1")
            .put("maxRuns", 2.5);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert !result.isValid() : "Should be invalid";
        assert result.getErrors().stream().anyMatch(e -> e.contains("Field 'runMode' must be a String"));
        assert result.getErrors().stream().anyMatch(e -> e.contains("Field 'minRuns' must be an Integer"));
        assert result.getErrors().stream().anyMatch(e -> e.contains("Field 'maxRuns' must be an Integer"));
        System.out.println("  ✓ OK");
    }

    private static void testNegativeValues() {
        System.out.println("Test: Negative values");
        JSONObject json = new JSONObject()
            .put("minRuns", -1)
            .put("maxRuns", 0)
            .put("timeBudgetMs", -5000L);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert !result.isValid() : "Should be invalid";
        assert result.getErrors().stream().anyMatch(e -> e.contains("Field 'minRuns' must be >= 1"));
        assert result.getErrors().stream().anyMatch(e -> e.contains("Field 'maxRuns' must be >= 1"));
        assert result.getErrors().stream().anyMatch(e -> e.contains("Field 'timeBudgetMs' must be >= 1"));
        System.out.println("  ✓ OK");
    }

    private static void testMinGreaterThanMax() {
        System.out.println("Test: minRuns > maxRuns");
        JSONObject json = new JSONObject()
            .put("minRuns", 10)
            .put("maxRuns", 5);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert !result.isValid() : "Should be invalid";
        assert result.getErrors().stream().anyMatch(e -> e.contains("cannot be greater than maxRuns"));
        System.out.println("  ✓ OK");
    }

    private static void testFixedRunsMismatch() {
        System.out.println("Test: fixed-runs mismatch");
        JSONObject json = new JSONObject()
            .put("runMode", "fixed-runs")
            .put("minRuns", 1)
            .put("maxRuns", 2);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert !result.isValid() : "Should be invalid";
        assert result.getErrors().stream().anyMatch(e -> e.contains("minRuns (1) must equal maxRuns (2)"));
        System.out.println("  ✓ OK");
    }

    private static void testFixedRunsWithTimeBudget() {
        System.out.println("Test: fixed-runs with timeBudgetMs");
        JSONObject json = new JSONObject()
            .put("runMode", "fixed-runs")
            .put("minRuns", 1)
            .put("maxRuns", 1)
            .put("timeBudgetMs", 1000);
        
        ValidationResult result = TestRequestValidator.validate(json);
        assert !result.isValid() : "Should be invalid";
        assert result.getErrors().stream().anyMatch(e -> e.contains("does not support 'timeBudgetMs'"));
        System.out.println("  ✓ OK");
    }
}
