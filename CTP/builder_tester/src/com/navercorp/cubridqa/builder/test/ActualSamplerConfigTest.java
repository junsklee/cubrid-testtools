package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderConfig;

public class ActualSamplerConfigTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== ActualSampler Config Test ===\n");

        BuilderConfig config = new BuilderConfig("/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf");

        // Test reflection access to properties
        try {
            java.lang.reflect.Field propertiesField = config.getClass().getDeclaredField("properties");
            propertiesField.setAccessible(true);
            java.util.Properties props = (java.util.Properties) propertiesField.get(config);

            System.out.println("Successfully accessed properties via reflection");
            System.out.println("actual_sampling_enabled = " + props.getProperty("actual_sampling_enabled", "NOT_FOUND"));
            System.out.println("test_container_pattern = " + props.getProperty("test_container_pattern", "NOT_FOUND"));

            System.out.println("\nAll properties containing 'actual' or 'container':");
            for (String key : props.stringPropertyNames()) {
                if (key.toLowerCase().contains("actual") || key.toLowerCase().contains("container")) {
                    System.out.println("  " + key + " = " + props.getProperty(key));
                }
            }
        } catch (Exception e) {
            System.out.println("ERROR: Failed to access properties: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n=== Test Complete ===");
    }
}
