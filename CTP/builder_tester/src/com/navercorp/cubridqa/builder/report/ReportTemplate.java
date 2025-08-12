package com.navercorp.cubridqa.builder.report;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ReportTemplate - Utility for loading the HTML report template.
 *
 * Loads the HTML from the classpath so we can support Java 8 without text blocks.
 */
public class ReportTemplate {

    private static final String[] CLASSPATH_CANDIDATES = new String[] {
        "report-template.html",
        "/com/navercorp/cubridqa/builder/report/report-template.html",
        "report_template.html",
        "/com/navercorp/cubridqa/builder/report/report_template.html"
    };

    // Disable template caching to ensure UI updates are reflected without restarting the service
    // If needed later, reintroduce caching guarded by a system property flag.
    private static volatile String cachedTemplate = null;

    public static String loadFromClasspath() throws IOException {
        // Always reload template to avoid stale UI
        for (String candidate : CLASSPATH_CANDIDATES) {
            InputStream inputStream = ReportTemplate.class.getResourceAsStream(candidate);
            if (inputStream != null) {
                try (InputStream in = inputStream) {
                    return readAllToString(in);
                }
            }
        }
        throw new FileNotFoundException("Report template not found on classpath");
    }

    private static String readAllToString(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}

