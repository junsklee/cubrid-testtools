package com.navercorp.cubridqa.builder.exec;

import java.io.*;
import java.util.logging.Logger;

public class ProcessIO {
    private static final Logger logger = Logger.getLogger(ProcessIO.class.getName());
    
    public static int runAndExitCode(ProcessBuilder basePb, String[] cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(basePb.directory());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drain(p.getInputStream());
        p.waitFor();
        return p.exitValue();
    }

    public static void runOrThrow(ProcessBuilder basePb, String[] cmd) throws IOException, InterruptedException {
        int ec = runAndExitCode(basePb, cmd);
        if (ec != 0) {
            throw new IOException("Command failed (" + ec + "): " + String.join(" ", cmd));
        }
    }
    
    public static void drain(InputStream is) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            while (r.readLine() != null) { /* discard */ }
        } catch (IOException ignore) {}
    }
    
    /**
     * StreamReader - Process stream reading in separate thread
     */
    public static class StreamReader extends Thread {
        private final InputStream is;
        private final String type;
        private final StringBuilder output = new StringBuilder();
        
        public StreamReader(InputStream is, String type) {
            this.is = is;
            this.type = type;
        }
        
        public String getOutput() {
            return output.toString();
        }
        
        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append("\n");
                    if (type.equals("ERROR") || type.equals("OUTPUT")) {
                        logger.info(type + ": " + line);
                    } else {
                        logger.fine(type + ": " + line);
                    }
                }
            } catch (IOException e) {
                logger.warning("Error reading " + type + " stream: " + e.getMessage());
            }
        }
    }
}