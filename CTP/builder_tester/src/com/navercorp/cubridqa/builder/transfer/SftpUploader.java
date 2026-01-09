package com.navercorp.cubridqa.builder.transfer;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

public final class SftpUploader {
    private static final Logger logger = Logger.getLogger(SftpUploader.class.getName());

    private SftpUploader() {
    }

    public static final class Options {
        private final String knownHostsPath;
        private final boolean strictHostKeyChecking;
        private final int connectTimeoutMs;

        public Options(String knownHostsPath, boolean strictHostKeyChecking, int connectTimeoutMs) {
            this.knownHostsPath = knownHostsPath;
            this.strictHostKeyChecking = strictHostKeyChecking;
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public String getKnownHostsPath() {
            return knownHostsPath;
        }

        public boolean isStrictHostKeyChecking() {
            return strictHostKeyChecking;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }
    }

    public static final class UploadResult {
        private final String remotePath;
        private final long bytes;
        private final long durationMs;

        public UploadResult(String remotePath, long bytes, long durationMs) {
            this.remotePath = remotePath;
            this.bytes = bytes;
            this.durationMs = durationMs;
        }

        public String getRemotePath() {
            return remotePath;
        }

        public long getBytes() {
            return bytes;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }

    public static UploadResult upload(String host, int port, String username, String password,
                                      String remoteDir, File localFile, Options options)
            throws JSchException, SftpException, IOException {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("SFTP host is required");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("SFTP username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("SFTP password is required");
        }
        if (localFile == null || !localFile.exists() || !localFile.isFile()) {
            throw new IllegalArgumentException("Local file does not exist: " + (localFile != null ? localFile.getPath() : "null"));
        }

        int timeoutMs = options != null ? options.getConnectTimeoutMs() : 10000;

        JSch jsch = new JSch();
        if (options != null && options.getKnownHostsPath() != null && !options.getKnownHostsPath().trim().isEmpty()) {
            jsch.setKnownHosts(options.getKnownHostsPath());
        }

        Session session = jsch.getSession(username, host, port);
        session.setPassword(password);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", (options != null && options.isStrictHostKeyChecking()) ? "yes" : "no");
        session.setConfig(config);

        ChannelSftp channel = null;
        long startMs = System.currentTimeMillis();
        try {
            session.connect(timeoutMs);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(timeoutMs);

            String uploadDir = ensureRemoteDir(channel, remoteDir);
            String remotePath = joinRemotePath(uploadDir, localFile.getName());

            try (InputStream in = new FileInputStream(localFile)) {
                channel.put(in, remotePath);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            return new UploadResult(remotePath, localFile.length(), durationMs);
        } finally {
            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception e) {
                    logger.fine("Failed to disconnect SFTP channel: " + e.getMessage());
                }
            }
            if (session != null) {
                try {
                    session.disconnect();
                } catch (Exception e) {
                    logger.fine("Failed to disconnect SFTP session: " + e.getMessage());
                }
            }
        }
    }

    private static String ensureRemoteDir(ChannelSftp channel, String remoteDir) throws SftpException {
        String normalized = (remoteDir == null || remoteDir.trim().isEmpty()) ? "." : remoteDir.trim();
        normalized = normalized.replace('\\', '/');
        if ("~".equals(normalized)) {
            normalized = ".";
        } else if (normalized.startsWith("~/")) {
            normalized = normalized.substring(2);
        }

        boolean absolute = normalized.startsWith("/");
        if (absolute) {
            channel.cd("/");
        }

        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty() || ".".equals(part)) {
                continue;
            }
            try {
                channel.cd(part);
            } catch (SftpException e) {
                channel.mkdir(part);
                channel.cd(part);
            }
        }
        return channel.pwd();
    }

    private static String joinRemotePath(String dir, String name) {
        if (dir == null || dir.isEmpty() || ".".equals(dir)) {
            return name;
        }
        if (dir.endsWith("/")) {
            return dir + name;
        }
        return dir + "/" + name;
    }
}
