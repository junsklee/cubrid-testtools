package com.navercorp.cubridqa.builder.tester;

import org.json.JSONObject;

/**
 * Represents a user-provided file attachment for Custom Script mode.
 * The attachment is written relative to the custom script execution directory.
 */
public final class CustomAttachment {
    private final String targetPath;
    private final String contentBase64;

    public CustomAttachment(String targetPath, String contentBase64) {
        this.targetPath = targetPath;
        this.contentBase64 = contentBase64;
    }

    public static CustomAttachment fromJson(JSONObject json) {
        String tp = json != null ? json.optString("targetPath", null) : null;
        String b64 = json != null ? json.optString("contentBase64", null) : null;
        return new CustomAttachment(tp, b64);
    }

    public String getTargetPath() {
        return targetPath;
    }

    public String getContentBase64() {
        return contentBase64;
    }

    public boolean isValid() {
        return targetPath != null && !targetPath.trim().isEmpty()
            && contentBase64 != null && !contentBase64.trim().isEmpty();
    }
}


