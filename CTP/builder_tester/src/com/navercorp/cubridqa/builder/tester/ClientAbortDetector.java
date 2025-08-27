package com.navercorp.cubridqa.builder.tester;

import java.io.IOException;

public class ClientAbortDetector {
    
    public static boolean isClientAbort(IOException ioe) {
        if (ioe == null) {
            return false;
        }
        
        String message = ioe.getMessage();
        if (message == null) {
            return false;
        }
        
        // Check for common client abort patterns
        return message.contains("Broken pipe") ||
               message.contains("Connection reset by peer") ||
               message.contains("Connection aborted") ||
               message.contains("Software caused connection abort") ||
               message.contains("insufficient bytes written");
    }
}