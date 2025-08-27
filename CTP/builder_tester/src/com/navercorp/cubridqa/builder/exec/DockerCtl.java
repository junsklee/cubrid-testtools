package com.navercorp.cubridqa.builder.exec;

import java.util.logging.Logger;

public class DockerCtl {
    
    public static void safeKillAndRemove(String containerName, Logger log) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                .redirectErrorStream(true)
                .start()
                .waitFor();
            log.info("Cleaned up container: " + containerName);
        } catch (Exception e) {
            log.warning("Failed to cleanup container '" + containerName + "': " + e.getMessage());
        }
    }
}