package com.navercorp.cubridqa.builder.tester;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class ExecutionTimeParser {
    
    /**
     * Extract execution time from test result content
     * Looking for patterns like "time=42" or "time: 42"
     */
    public static String parse(String resultContent) {
        if (resultContent == null || resultContent.isEmpty()) {
            return null;
        }
        
        // Look for time pattern in result
        Pattern pattern = Pattern.compile("time[=:]\\s*(\\d+)");
        Matcher matcher = pattern.matcher(resultContent);
        if (matcher.find()) {
            return matcher.group(1) + "s";
        }
        return null;
    }
}