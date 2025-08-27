package com.navercorp.cubridqa.builder.exec;

import java.io.File;

public class CtpEnvResolver {
    
    public static String findCTPHome() {
        String ctpHome = System.getenv("CTP_HOME");
        if (ctpHome != null && new File(ctpHome).exists()) {
            return ctpHome;
        }
        
        // Try common locations
        String[] commonPaths = {
            System.getProperty("user.home") + "/cubrid-testtools/CTP"
        };
        
        for (String path : commonPaths) {
            File ctpDir = new File(path);
            if (ctpDir.exists() && new File(ctpDir, "shell").exists()) {
                return path;
            }
        }
        
        return System.getProperty("user.home") + "/cubrid-testtools/CTP";
    }
}