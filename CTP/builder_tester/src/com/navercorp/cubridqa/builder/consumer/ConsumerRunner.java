package com.navercorp.cubridqa.builder.consumer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.navercorp.cubridqa.builder.Tester;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.logging.RequestIds;
import com.navercorp.cubridqa.builder.tester.SafeIo;

/**
 * Runs the cases of one Builder-Tester request on this QAHome consumer VM, between regression
 * jobs. CTP/common/ext/run_builder_tester.sh starts it for a message on the Builder-Tester queue
 * (start_consumer.sh -exec run_builder_tester) with the message's Builder URL, request ID and
 * slot.
 *
 * <p>It starts the VM-mode tester in this process on a free port, then, until the Builder has no
 * case left for it: leases a case, posts it to the tester's /test, collects the attempt logs from
 * the tester's /log and posts the response back with them. The settings the cases see come from
 * this VM's regression conf (CTP/conf/shell_template.conf), as CTP shell would apply them, so a
 * case runs here as it does in regression.
 *
 * <p>Usage: ConsumerRunner &lt;builder url&gt; &lt;request id&gt; &lt;slot&gt; &lt;runner home&gt; &lt;CTP home&gt;
 */
public final class ConsumerRunner {
    /** Remote added to the testcase checkout for the run, with the VM's git credentials. */
    static final String AUTH_REMOTE = "bt_consumer_auth";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int CALL_TIMEOUT_MS = 120_000;

    private final String builderUrl;
    private final String requestId;
    private final String slot;
    private final Path home;
    private final Path ctpHome;
    private final String runnerName;
    private int testerPort;

    ConsumerRunner(String builderUrl, String requestId, String slot, Path home, Path ctpHome) {
        this.builderUrl = builderUrl;
        this.requestId = requestId;
        this.slot = slot;
        this.home = home;
        this.ctpHome = ctpHome;
        this.runnerName = System.getProperty("user.name") + "@" + hostName();
    }

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("usage: ConsumerRunner <builder url> <request id> <slot> <runner home> <CTP home>");
            System.exit(2);
        }
        String builderUrl = args[0].replaceAll("/+$", "");
        if (!builderUrl.matches("https?://[A-Za-z0-9.:\\[\\]_-]+") || !RequestIds.isValid(args[1]) || !args[2].matches("[0-9a-f]{32}")) {
            System.err.println("[builder-tester] refusing malformed message values: " + String.join(" ", args));
            System.exit(2);
        }
        int code;
        try {
            code = new ConsumerRunner(builderUrl, args[1], args[2], Paths.get(args[3]).toAbsolutePath(),
                Paths.get(args[4]).toAbsolutePath()).run();
        } catch (Throwable t) {
            say("runner failed: " + t);
            t.printStackTrace();
            code = 1;
        }
        // The tester's HTTP server and executors are not daemon threads
        System.exit(code);
    }

    int run() throws Exception {
        JSONObject config = getJson(builderUrl + "/consumer/config?requestId=" + enc(requestId) + "&slot=" + enc(slot));
        if (config.optInt("protocol") != ConsumerHandler.PROTOCOL) {
            say("Builder speaks runner protocol " + config.optInt("protocol") + ", this runner " + ConsumerHandler.PROTOCOL
                + "; nothing run");
            return 3;
        }
        if (!config.optBoolean("active")) {
            say("request " + requestId + " has no work for this message (finished or cancelled); nothing to do");
            return 0;
        }
        long heartbeatMs = TimeUnit.SECONDS.toMillis(Math.max(5, config.optInt("heartbeatSeconds", 20)));

        Files.createDirectories(home.resolve("conf"));
        JSONObject overrides = config.optJSONObject("tester") != null ? config.getJSONObject("tester") : new JSONObject();
        Properties testerConf = testerConf(overrides);
        Path shellTcDir = Paths.get(testerConf.getProperty("shell_tc_dir"));
        Tester tester = null;
        int cases = 0;
        try {
            if (addAuthRemote(shellTcDir) && !overrides.has("shell_tc_preferred_remote")) {
                testerConf.setProperty("shell_tc_preferred_remote", AUTH_REMOTE);
            }
            Path confFile = home.resolve("conf").resolve("tester_consumer.conf");
            try (OutputStream out = Files.newOutputStream(confFile)) {
                testerConf.store(out, "Written by ConsumerRunner for request " + requestId + "; replaced on every run");
            }
            say("request " + requestId + ": tester on 127.0.0.1:" + testerPort + ", CUBRID " + testerConf.getProperty("vm_cubrid_home")
                + ", testcases " + shellTcDir + ", case timeout " + testerConf.getProperty("vm_testcase_timeout_in_secs") + "s");

            quietConsoleLogging();
            System.setProperty("tester.project.root", home.toString());
            tester = new Tester(new Config(confFile.toString()));
            tester.start();
            while (true) {
                JSONObject lease = lease();
                if (lease == null || lease.optBoolean("done") || !lease.has("jobId")) {
                    break;
                }
                cases++;
                if (!runCase(lease.getString("jobId"), lease.getJSONObject("testRequest"), heartbeatMs)) {
                    // The Builder took the case back (it thought this VM silent, or the case
                    // timed out). The request is cancelled in this tester now, so leave the rest
                    // to other VMs.
                    say("stopping: the Builder withdrew a case from this VM");
                    break;
                }
            }
        } finally {
            if (tester != null) {
                try {
                    postLocal("/finalize-request", new JSONObject().put("requestId", requestId), CALL_TIMEOUT_MS);
                } catch (Exception e) {
                    say("finalize failed: " + e.getMessage());
                }
                tester.stop();
            }
            // Also removes one left by an earlier runner that was killed
            git(shellTcDir, "remote", "remove", AUTH_REMOTE);
            git(shellTcDir, "worktree", "prune");
            // The request's worktrees and attempt directories; only under this runner's home, in
            // case consumer_tester.work_dir points somewhere shared
            Path workDir = Paths.get(testerConf.getProperty("work_dir")).toAbsolutePath().normalize();
            if (workDir.startsWith(home)) {
                SafeIo.deleteDirectory(workDir.toFile());
            }
        }
        say("request " + requestId + ": ran " + cases + " case(s); returning the VM to the queue");
        return 0;
    }

    /** The next case, retrying while the Builder is briefly unreachable (a restart). Null = stop. */
    private JSONObject lease() throws InterruptedException {
        JSONObject body = new JSONObject().put("requestId", requestId).put("slot", slot).put("runner", runnerName);
        for (int attempt = 1; ; attempt++) {
            try {
                return postJson(builderUrl + "/consumer/lease", body, CALL_TIMEOUT_MS);
            } catch (IOException e) {
                if (attempt >= 6) {
                    say("giving up on the Builder: " + e.getMessage());
                    return null;
                }
                say("lease failed (" + e.getMessage() + "); retrying");
                Thread.sleep(10_000L);
            }
        }
    }

    /**
     * Runs one leased case and delivers its result. Returns false when the Builder withdrew the
     * case or refused the result, after which this runner takes no more cases.
     */
    private boolean runCase(String jobId, JSONObject testRequest, long heartbeatMs) throws Exception {
        String name = testRequest.optString("testName", testRequest.optString("testPath"));
        say("running " + name);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean withdrawn = new AtomicBoolean(false);
        // Until the result is delivered: delivery retries must not let the lease lapse
        Thread heartbeat = new Thread(() -> heartbeat(jobId, heartbeatMs, running, delivered, withdrawn), "consumer-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
        try {
            int status;
            byte[] body;
            try {
                HttpURLConnection conn = open("http://127.0.0.1:" + testerPort + "/test", "POST", 0);
                conn.setRequestProperty("Content-Type", "application/json");
                write(conn, testRequest.toString().getBytes(StandardCharsets.UTF_8));
                status = conn.getResponseCode();
                body = read(conn);
            } catch (IOException e) {
                status = 500;
                body = new JSONObject().put("status", "execution_error")
                    .put("message", "Consumer VM " + runnerName + " could not run the case: " + e.getMessage())
                    .toString().getBytes(StandardCharsets.UTF_8);
            } finally {
                running.set(false);
            }
            if (withdrawn.get()) {
                return false;
            }

            // The Builder cannot fetch the logs from this VM later, so they travel with the result
            Map<String, byte[]> files = new LinkedHashMap<>();
            try {
                JSONObject response = new JSONObject(new String(body, StandardCharsets.UTF_8));
                JSONArray metadata = response.optJSONArray("attemptLogMetadata");
                for (int i = 0; metadata != null && i < metadata.length(); i++) {
                    String logName = metadata.getJSONObject(i).optString("logFileName", "");
                    if (logName.isEmpty() || files.containsKey(logName)) {
                        continue;
                    }
                    try {
                        HttpURLConnection conn = open("http://127.0.0.1:" + testerPort + "/log/" + enc(logName)
                            + "?requestId=" + enc(requestId), "GET", CALL_TIMEOUT_MS);
                        if (conn.getResponseCode() == 200) {
                            files.put(logName, read(conn));
                        } else {
                            say("log " + logName + " not available (HTTP " + conn.getResponseCode() + ")");
                        }
                    } catch (IOException e) {
                        say("could not read log " + logName + ": " + e.getMessage());
                    }
                }
                say(name + ": " + response.optString("status") + " (" + files.size() + " log file(s))");
            } catch (Exception e) {
                say(name + ": unparsable tester response (HTTP " + status + ")");
            }
            return deliver(jobId, name, status, body, files);
        } finally {
            delivered.set(true);
            heartbeat.interrupt();
        }
    }

    /**
     * Posts the result. Retries connection failures and server errors (the Builder restarting);
     * returns false when the Builder refuses it (no longer expected, too large).
     */
    private boolean deliver(String jobId, String name, int status, byte[] body, Map<String, byte[]> files)
            throws IOException, InterruptedException {
        String boundary = "bt-consumer-" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipart = multipart(boundary, body, files);
        for (int attempt = 1; ; attempt++) {
            String failure;
            try {
                HttpURLConnection conn = open(builderUrl + "/consumer/result?jobId=" + enc(jobId), "POST", CALL_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("X-Tester-Status", String.valueOf(status));
                write(conn, multipart);
                int code = conn.getResponseCode();
                String reply = new String(read(conn), StandardCharsets.UTF_8);
                if (code == 200) {
                    return true;
                }
                if (code < 500) {
                    say("the Builder refused the result of " + name + " (HTTP " + code + "): " + reply);
                    return false;
                }
                failure = "HTTP " + code;
            } catch (IOException e) {
                failure = e.getMessage();
            }
            if (attempt >= 10) {
                throw new IOException("could not deliver the result of " + name + ": " + failure);
            }
            say("delivering the result of " + name + " failed (" + failure + "); retrying");
            Thread.sleep(Math.min(60_000L, 5000L * attempt));
        }
    }

    /**
     * Keeps the lease alive until the result is delivered. When the Builder withdraws the case
     * while it runs, cancels it in the tester (which cancels the request there: this runner takes
     * no more cases afterwards).
     */
    private void heartbeat(String jobId, long intervalMs, AtomicBoolean running, AtomicBoolean delivered,
                           AtomicBoolean withdrawn) {
        while (!delivered.get()) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            try {
                JSONObject reply = postJson(builderUrl + "/consumer/heartbeat", new JSONObject().put("jobId", jobId), 30_000);
                if (reply.optBoolean("stop") && !withdrawn.get() && !delivered.get()) {
                    withdrawn.set(true);
                    if (running.get()) {
                        say("the Builder withdrew the case; cancelling it");
                        postLocal("/cancel-request", new JSONObject().put("requestId", requestId), CALL_TIMEOUT_MS);
                    }
                }
            } catch (Exception e) {
                // The Builder may be restarting; the lease survives consumer_lease_timeout_seconds
                say("heartbeat failed: " + e.getMessage());
            }
        }
    }

    /**
     * The tester conf: VM-mode defaults, the regression settings of this VM's CTP shell conf,
     * then the Builder's consumer_tester.* overrides. The port and mode are always this runner's.
     */
    Properties testerConf(JSONObject overrides) throws IOException {
        Properties regression = readRegressionConf();
        String userHome = System.getProperty("user.home");
        Properties conf = new Properties();
        conf.setProperty("shell_tc_dir", defaultShellTcDir(regression, userHome));
        conf.setProperty("sql_tc_dir", defaultSqlTcDir(ctpHome, userHome));
        conf.setProperty("shell_tc_branch", "develop");
        conf.setProperty("shell_tc_preferred_remote", "upstream");
        conf.setProperty("work_dir", home.resolve("work").toString());
        conf.setProperty("cubrid_src_dir", home.resolve("unused_cubrid_src").toString());
        String cubrid = System.getenv("CUBRID");
        conf.setProperty("vm_cubrid_home", cubrid != null && !cubrid.trim().isEmpty() ? cubrid.trim() : userHome + "/CUBRID");
        conf.setProperty("vm_ctp_home", ctpHome.toString());
        int timeout = parseInt(regression.getProperty("testcase_timeout_in_secs"), 7200);
        conf.setProperty("vm_testcase_timeout_in_secs", String.valueOf(timeout));
        conf.setProperty("test_read_timeout_minutes", String.valueOf(timeout / 60 + 10));
        conf.setProperty("vm_ssh_port", sshPort(regression));
        putIfSet(conf, "vm_big_space_dir", expand(regression.getProperty("large_space_dir"), userHome));
        // CTP shell exports CUBRID_CHARSET=<cubrid_db_charset>, en_US when unset
        conf.setProperty("vm_default_dbcharset", nonEmpty(regression.getProperty("cubrid_db_charset"), "en_US"));
        putIfSet(conf, "vm_ignore_core_by_keywords", regression.getProperty("ignore_core_by_keywords"));
        conf.setProperty("vm_keep_artifacts", "fail");
        conf.setProperty("stats_enabled", "false");
        conf.setProperty("wal_collection_enabled", "false");
        conf.setProperty("actual_sampling_enabled", "false");
        conf.setProperty("max_request_logs", "20");
        conf.setProperty("enable_request_grouping", "true");
        if (overrides != null) {
            for (String key : overrides.keySet()) {
                conf.setProperty(key, expand(overrides.optString(key), userHome));
            }
        }
        testerPort = freePort();
        conf.setProperty("tester_port", String.valueOf(testerPort));
        // Only this runner talks to the tester: /test runs any script it is given
        conf.setProperty("tester_bind_address", "127.0.0.1");
        // The testcase checkouts belong to regression: fetch the requested commit into them and run
        // from per-request worktrees, but never check out, clean or reset their working trees
        // (per_request mode fast-forwards the base checkout to the requested branch)
        conf.setProperty("shell_tc_sync_mode", "disabled");
        conf.setProperty("sql_tc_sync_mode", "disabled");
        conf.setProperty("execution_mode", "vm");
        return conf;
    }

    /**
     * The VM's shell regression conf: the template run_shell.sh copies, or, without one, the
     * conf of the last regression run.
     */
    Properties readRegressionConf() throws IOException {
        Properties props = new Properties();
        for (String name : new String[] {"shell_template.conf", "shell_runtime.conf"}) {
            File file = ctpHome.resolve("conf").resolve(name).toFile();
            if (file.isFile()) {
                try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    props.load(in);
                }
                say("regression settings from " + file);
                break;
            }
        }
        return props;
    }

    /** The checkout regression runs from: the repo holding its scenario (…/shell), when it is one. */
    static String defaultShellTcDir(Properties regression, String userHome) {
        String scenario = expand(regression.getProperty("scenario"), userHome);
        if (scenario != null) {
            Path dir = Paths.get(scenario).normalize();
            if (dir.getFileName() != null && "shell".equals(dir.getFileName().toString()) && dir.getParent() != null
                    && dir.getParent().resolve(".git").toFile().exists()) {
                return dir.getParent().toString();
            }
        }
        return userHome + "/cubrid-testcases-private-ex";
    }

    /**
     * The SQL testcase checkout regression uses: run_sql.sh runs ${CTP_HOME}/../cubrid-testcases.
     */
    static String defaultSqlTcDir(Path ctpHome, String userHome) {
        Path sibling = ctpHome.toAbsolutePath().normalize().getParent();
        if (sibling != null && sibling.resolve("cubrid-testcases").resolve(".git").toFile().exists()) {
            return sibling.resolve("cubrid-testcases").toString();
        }
        return userHome + "/cubrid-testcases";
    }

    static String sshPort(Properties regression) {
        String port = regression.getProperty("default.ssh.port");
        if (port == null) {
            for (String key : regression.stringPropertyNames()) {
                if (key.startsWith("env.") && key.endsWith(".ssh.port")) {
                    port = regression.getProperty(key);
                    break;
                }
            }
        }
        return nonEmpty(port, "22");
    }

    /**
     * Adds a remote with the VM's git credentials (CTP/conf/common.conf git_user and git_pwd), as
     * CTP's run_git_update does for regression: the testcase repo is private and its origin URL
     * usually carries none. Returns false when there is nothing to add.
     */
    boolean addAuthRemote(Path repo) {
        try {
            File commonConf = ctpHome.resolve("conf").resolve("common.conf").toFile();
            if (!commonConf.isFile() || !repo.resolve(".git").toFile().exists()) {
                return false;
            }
            Properties common = new Properties();
            try (Reader in = new InputStreamReader(new FileInputStream(commonConf), StandardCharsets.UTF_8)) {
                common.load(in);
            }
            String user = common.getProperty("git_user", "").trim();
            String password = common.getProperty("git_pwd", "").trim();
            String origin = git(repo, "remote", "get-url", "origin").trim();
            int at = origin.indexOf("github.com");
            if (user.isEmpty() || password.isEmpty() || !origin.startsWith("https://") || at < 0) {
                return false;
            }
            String url = "https://" + URLEncoder.encode(user, "UTF-8") + ":" + URLEncoder.encode(password, "UTF-8") + "@"
                + origin.substring(at);
            git(repo, "remote", "remove", AUTH_REMOTE);
            // Written to .git/config directly, as `git remote add` would, so the password never
            // appears on a command line (ps). Removed again when the runner ends.
            File gitConfig = repo.resolve(".git").resolve("config").toFile();
            if (!gitConfig.isFile() || url.contains("\"") || url.contains("\n")) {
                return false;
            }
            String section = "[remote \"" + AUTH_REMOTE + "\"]\n\turl = " + url + "\n\tfetch = +refs/heads/*:refs/remotes/"
                + AUTH_REMOTE + "/*\n";
            Files.write(gitConfig.toPath(), section.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            say("could not add the authenticated testcase remote: " + e.getMessage());
            return false;
        }
    }

    private static String git(Path repo, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        for (String arg : args) {
            command.add(arg);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = pb.start();
            byte[] out = readAll(process.getInputStream());
            process.waitFor(60, TimeUnit.SECONDS);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Only warnings reach the consumer's console; the tester's request logs keep the rest. */
    private static void quietConsoleLogging() {
        System.setProperty("builder_tester.console_level", "WARNING");
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            handler.setLevel(Level.WARNING);
        }
    }

    static byte[] multipart(String boundary, byte[] response, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";
        out.write(("--" + boundary + crlf + "Content-Disposition: form-data; name=\"response\"" + crlf
            + "Content-Type: application/json; charset=utf-8" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(response);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        int i = 0;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            out.write(("--" + boundary + crlf + "Content-Disposition: form-data; name=\"file" + (i++) + "\"; filename=\""
                + file.getKey().replace("\"", "_") + "\"" + crlf + "Content-Type: application/octet-stream" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
            out.write(file.getValue());
            out.write(crlf.getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void postLocal(String path, JSONObject body, int timeoutMs) throws IOException {
        HttpURLConnection conn = open("http://127.0.0.1:" + testerPort + path, "POST", timeoutMs);
        conn.setRequestProperty("Content-Type", "application/json");
        write(conn, body.toString().getBytes(StandardCharsets.UTF_8));
        conn.getResponseCode();
        read(conn);
    }

    private static JSONObject getJson(String url) throws IOException {
        HttpURLConnection conn = open(url, "GET", CALL_TIMEOUT_MS);
        int code = conn.getResponseCode();
        String body = new String(read(conn), StandardCharsets.UTF_8);
        if (code != 200) {
            throw new IOException("GET " + url + " returned HTTP " + code + ": " + body);
        }
        return new JSONObject(body);
    }

    private static JSONObject postJson(String url, JSONObject payload, int timeoutMs) throws IOException {
        HttpURLConnection conn = open(url, "POST", timeoutMs);
        conn.setRequestProperty("Content-Type", "application/json");
        write(conn, payload.toString().getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();
        String body = new String(read(conn), StandardCharsets.UTF_8);
        if (code != 200) {
            throw new IOException("POST " + url + " returned HTTP " + code + ": " + body);
        }
        return new JSONObject(body);
    }

    private static HttpURLConnection open(String url, String method, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        return conn;
    }

    private static void write(HttpURLConnection conn, byte[] body) throws IOException {
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
    }

    private static byte[] read(HttpURLConnection conn) throws IOException {
        InputStream in;
        try {
            in = conn.getInputStream();
        } catch (IOException e) {
            in = conn.getErrorStream();
        }
        if (in == null) {
            return new byte[0];
        }
        try (InputStream stream = in) {
            return readAll(stream);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static String expand(String value, String userHome) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("~/") || v.equals("~")) {
            v = userHome + v.substring(1);
        }
        return v.replace("${HOME}", userHome).replace("$HOME", userHome);
    }

    private static void putIfSet(Properties props, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            props.setProperty(key, value.trim());
        }
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static void say(String message) {
        System.out.println("[builder-tester] " + message);
    }
}
