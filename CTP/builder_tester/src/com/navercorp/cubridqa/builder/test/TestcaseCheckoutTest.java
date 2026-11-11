package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.exec.EnvScriptFactory;
import com.navercorp.cubridqa.builder.exec.VmScriptFactory;
import com.navercorp.cubridqa.builder.git.GitCredentials;
import com.navercorp.cubridqa.builder.git.TcRepoSync;
import com.navercorp.cubridqa.builder.tester.CustomAttachment;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Testcase checkouts as cases see them. A request's testcase workspace is on a named branch,
 * CTP style (cases read it with git rev-parse --abbrev-ref HEAD, then _ -> /), and checkouts the
 * cases use in place (~/cubrid-testcases) follow the request's branch. Also the VM CTP's
 * common.conf, an entry script sent as exact bytes, and the result_file CTP's init writes to
 * before it sets it.
 */
public class TestcaseCheckoutTest {
    private static final Logger LOG = Logger.getLogger(TestcaseCheckoutTest.class.getName());

    public static void main(String[] args) throws Exception {
        System.out.println("=== TestcaseCheckoutTest Suite ===\n");
        Path root = Files.createTempDirectory("tc_checkout_test");
        try {
            Path origin = seedOrigin(root);
            namedBranchWorkspaces(root, origin);
            baseCheckoutFollowsRequest(root, origin);
            commonConf(root);
            entryScriptBytes(root);
            resultFileBeforeInit();
            vmCtpInstalled(root);
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        System.out.println("\n=== All TestcaseCheckoutTest tests passed! ===");
    }

    /** origin.git with develop (2 commits) and release/11.4 (3 commits, sql/ tree). */
    private static Path seedOrigin(Path root) throws Exception {
        Path origin = root.resolve("origin.git");
        git(root, "init", "-q", "--bare", origin.toString());
        Path seed = root.resolve("seed");
        git(root, "clone", "-q", origin.toString(), seed.toString());
        git(seed, "checkout", "-q", "-b", "develop");
        commit(seed, "sql/_01/cases/a.sql", "select 1;");
        commit(seed, "sql/_01/cases/b.sql", "select 2;");
        git(seed, "push", "-q", "origin", "develop");
        git(seed, "checkout", "-q", "-b", "release/11.4");
        commit(seed, "sql/_02/cases/c.sql", "select 3;");
        git(seed, "push", "-q", "origin", "release/11.4");
        return origin;
    }

    static void namedBranchWorkspaces(Path root, Path origin) throws Exception {
        Path base = root.resolve("shell_base");
        git(root, "clone", "-q", "-b", "develop", origin.toString(), base.toString());
        TcRepoSync sync = new TcRepoSync(new TcRepoSync.RepoSpec("shell testcases", base.toString(), "develop",
            "origin", TcRepoSync.SyncMode.PER_REQUEST, 0, root.resolve("requests").toString(), false, base.toString()));
        String releaseHead = git(root.resolve("seed"), "rev-parse", "release/11.4");
        String releaseOlder = git(root.resolve("seed"), "rev-parse", "release/11.4~1");

        Path a = sync.prepareRequestWorkspace(LOG, "req_a", "release/11.4", null);
        check("release_11.4".equals(git(a, "rev-parse", "--abbrev-ref", "HEAD")), "workspace HEAD is a named branch, CTP style: "
            + git(a, "rev-parse", "--abbrev-ref", "HEAD"));
        check(releaseHead.equals(git(a, "rev-parse", "HEAD")), "workspace at the branch head");
        check("release/11.4".equals(git(a, "rev-parse", "--abbrev-ref", "HEAD").replace('_', '/')),
            "the case's _ -> / gives the request's branch");
        // Another request on the same branch, pinned to another commit: worktrees could not both be on it
        Path b = sync.prepareRequestWorkspace(LOG, "req_b", "release/11.4", releaseOlder);
        check("release_11.4".equals(git(b, "rev-parse", "--abbrev-ref", "HEAD")), "a second request on the same branch");
        check(releaseOlder.equals(git(b, "rev-parse", "HEAD")), "second request at its own commit");
        check(releaseHead.equals(git(a, "rev-parse", "HEAD")), "first request did not move");
        Path c = sync.prepareRequestWorkspace(LOG, "req_c", "", null);
        check("develop".equals(git(c, "rev-parse", "--abbrev-ref", "HEAD")), "no branch requested -> configured develop");

        check(git(base, "remote", "get-url", "origin").equals(git(a, "remote", "get-url", "origin")),
            "workspace origin is the base repo's (convert_to_git_url.sh, run_git_update read it)");
        List<Path> borrowed = TcRepoSync.borrowedObjectDirs(a);
        check(borrowed.size() == 1 && borrowed.get(0).equals(base.resolve(".git/objects").toAbsolutePath().normalize()),
            "workspace borrows the base repo's objects: " + borrowed);
        check("release_11.4".equals(git(base.getParent(), "-C", a.toString(), "rev-parse", "--abbrev-ref", "HEAD")),
            "git works in the workspace from outside");
        check(a.equals(sync.prepareRequestWorkspace(LOG, "req_a", "release/11.4", null)), "same request reuses its workspace");
        check(!git(base, "worktree", "list").contains(a.toString()), "no worktree registered in the base repo");

        // A detached worktree from before named branches is not reused
        Path legacyRoot = root.resolve("requests").resolve("req_legacy");
        Files.createDirectories(legacyRoot);
        git(base, "worktree", "add", "-q", "--detach", legacyRoot.resolve("repo").toString(), releaseHead);
        Path legacy = sync.prepareRequestWorkspace(LOG, "req_legacy", "release/11.4", releaseHead);
        check("release_11.4".equals(git(legacy, "rev-parse", "--abbrev-ref", "HEAD")), "legacy detached worktree replaced");

        sync.cleanupRequestWorkspace(LOG, "req_a");
        check(!Files.exists(a), "cleanup removes the workspace");
        check(Files.isDirectory(base.resolve(".git/objects")), "cleanup leaves the base repo");
    }

    static void baseCheckoutFollowsRequest(Path root, Path origin) throws Exception {
        Path base = root.resolve("cubrid-testcases");
        git(root, "clone", "-q", "-b", "develop", origin.toString(), base.toString());
        git(base, "reset", "-q", "--hard", "HEAD~1");
        TcRepoSync sibling = new TcRepoSync(new TcRepoSync.RepoSpec("cubrid-testcases", base.toString(), "develop",
            "origin", TcRepoSync.SyncMode.PER_REQUEST, 0, root.resolve("sibling_requests").toString(), false, base.toString()));
        Path seed = root.resolve("seed");
        String releaseHead = git(seed, "rev-parse", "release/11.4");

        String at = sibling.checkoutBaseForRequest(LOG, "req_1", "release/11.4");
        check(releaseHead.equals(at), "checkout on the request branch head: " + at);
        check("release_11.4".equals(git(base, "rev-parse", "--abbrev-ref", "HEAD")), "local branch named as run_git_update names it");
        check("origin/release/11.4".equals(git(base, "rev-parse", "--abbrev-ref", "@{u}")), "tracks the remote branch (-t)");

        // What a killed cbrd_23613_6 case leaves: sql/ moved aside, a stray file
        Files.move(base.resolve("sql"), base.resolve("org_sql"));
        Files.write(base.resolve("stray.txt"), "x".getBytes(StandardCharsets.UTF_8));
        check(releaseHead.equals(sibling.checkoutBaseForRequest(LOG, "req_1", "release/11.4")), "next case, same head");
        check(Files.isRegularFile(base.resolve("sql/_02/cases/c.sql")), "sql/ restored for the next case");
        check(!Files.exists(base.resolve("org_sql")) && !Files.exists(base.resolve("stray.txt")), "leftovers cleaned (-df)");

        // The remote moves on: the request keeps its tree, a new request gets the new head
        git(seed, "checkout", "-q", "release/11.4");
        commit(seed, "sql/_02/cases/d.sql", "select 4;");
        git(seed, "push", "-q", "origin", "release/11.4");
        String newHead = git(seed, "rev-parse", "HEAD");
        check(releaseHead.equals(sibling.checkoutBaseForRequest(LOG, "req_1", "release/11.4")), "pinned for the whole request");
        check(newHead.equals(sibling.checkoutBaseForRequest(LOG, "req_2", "release/11.4")), "a new request takes the new head");
        check(sibling.checkoutBaseForRequest(LOG, "req_3", null) != null
            && "develop".equals(git(base, "rev-parse", "--abbrev-ref", "HEAD")), "no branch requested -> configured develop");

        String before = git(base, "rev-parse", "HEAD");
        check(sibling.checkoutBaseForRequest(LOG, "req_4", "feature/not_here") == null, "branch on no remote -> left alone");
        check(before.equals(git(base, "rev-parse", "HEAD")), "unchanged for an unknown branch");

        TcRepoSync disabled = new TcRepoSync(new TcRepoSync.RepoSpec("cubrid-testcases", base.toString(), "develop",
            "origin", TcRepoSync.SyncMode.DISABLED, 0, root.resolve("sibling_requests").toString(), false, base.toString()));
        check(disabled.checkoutBaseForRequest(LOG, "req_5", "release/11.4") == null, "sync disabled -> never moved");
        check(before.equals(git(base, "rev-parse", "HEAD")), "unchanged when disabled");

        TcRepoSync missing = new TcRepoSync(new TcRepoSync.RepoSpec("cubrid-testcases-private",
            root.resolve("cubrid-testcases-private").toString(), "develop", "origin", TcRepoSync.SyncMode.PER_REQUEST, 0,
            root.resolve("sibling_requests").toString(), false, root.resolve("cubrid-testcases-private").toString()));
        check(missing.checkoutBaseForRequest(LOG, "req_6", "develop") == null, "missing checkout -> skipped");
    }

    static void commonConf(Path root) throws Exception {
        Path ctp = root.resolve("ctp_new");
        Path conf = ctp.resolve("conf/common.conf");
        List<String> added = GitCredentials.installCommonConf(ctp, "qa", "token", "qa@example.com");
        check(added.size() == 3, "new common.conf gets all three keys: " + added);
        String text = new String(Files.readAllBytes(conf), StandardCharsets.UTF_8);
        check(text.contains("git_user=qa\n") && text.contains("git_pwd=token\n") && text.contains("git_email=qa@example.com\n"),
            "keys as CTP's ini.sh reads them: " + text);
        check("rw-------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(conf))), "owner-only permissions");

        Path ctpOld = root.resolve("ctp_old");
        Path oldConf = ctpOld.resolve("conf/common.conf");
        Files.createDirectories(oldConf.getParent());
        Files.write(oldConf, "default_ssh_port=22\ngit_user=regression".getBytes(StandardCharsets.UTF_8));
        added = GitCredentials.installCommonConf(ctpOld, "qa", "", "");
        check(added.size() == 2 && added.contains("git_pwd") && added.contains("git_email"), "only missing keys added: " + added);
        text = new String(Files.readAllBytes(oldConf), StandardCharsets.UTF_8);
        check(text.startsWith("default_ssh_port=22\ngit_user=regression\n") && text.contains("\ngit_pwd=\n"),
            "existing keys kept, appended on a line of their own: " + text);
        check(GitCredentials.installCommonConf(ctpOld, "x", "y", "z").isEmpty(), "complete file left alone");
    }

    /** customShellScriptBase64 reaches the case byte for byte (EUC-KR script). */
    static void entryScriptBytes(Path root) throws Exception {
        byte[] script = ("#!/bin/bash\n. $init_path/init.sh\ninit test\necho '한글 테스트' > out.txt\nfinish\n")
            .getBytes(Charset.forName("EUC-KR"));
        String b64 = Base64.getEncoder().encodeToString(script);
        JSONObject json = new JSONObject()
            .put("testPath", "shell/_06_issues/bug_bts_5730/cases/bug_bts_5730.sh")
            .put("buildPackage", "http://x/cubrid.tar.gz")
            .put("testScript", "bug_bts_5730.sh")
            .put("customShellScriptBase64", b64)
            .put("customAttachments", new JSONArray().put(new JSONObject()
                .put("targetPath", "data/in.txt").put("contentBase64", Base64.getEncoder().encodeToString("in".getBytes()))));
        TestRequest request = new TestRequest(json);
        check(request.hasCustomShellScript() && request.getCustomShellScript() == null, "bytes, not text");
        List<CustomAttachment> attachments = request.getCustomAttachments();
        check(attachments.size() == 2 && "bug_bts_5730.sh".equals(attachments.get(1).getTargetPath()),
            "entry script is the last attachment");

        TestRequest both = new TestRequest(new JSONObject(json.toString()).put("customShellScript", "echo text"));
        check(both.getCustomShellScript() == null, "the exact bytes win over a text script");
        TestRequest text = new TestRequest(new JSONObject(json.toString()).put("customShellScriptBase64", "")
            .put("customShellScript", "echo text"));
        check("echo text".equals(text.getCustomShellScript()) && text.getCustomAttachments().size() == 1, "text script as before");

        // The Docker runner materializes it with base64 -d: run that part of the generated script
        String docker = EnvScriptFactory.createDockerOptimizedScript("bug_bts_5730.sh", "bug_bts_5730", null,
            "shell/_06_issues/bug_bts_5730/cases", "/workspace/CTP", EnvScriptFactory.TESTCASE_MOUNT,
            request.getCustomShellScript(), attachments, false, "req_x");
        check(!docker.contains("CUSTOM_SCRIPT_EOF"), "no text heredoc for a byte-exact script");
        int start = docker.indexOf("decode_b64_to_file() {");
        int end = docker.indexOf("write_attachment 'bug_bts_5730.sh'");
        end = docker.indexOf('\n', end) + 1;
        Path dir = Files.createDirectories(root.resolve("docker_case"));
        Path part = root.resolve("attachments.sh");
        Files.write(part, docker.substring(start, end).getBytes(StandardCharsets.UTF_8));
        ProcessBuilder pb = new ProcessBuilder("bash", part.toString()).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("TESTCASE_DIR", dir.toString());
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        check(p.waitFor() == 0, "attachments block runs: " + out);
        check(java.util.Arrays.equals(script, Files.readAllBytes(dir.resolve("bug_bts_5730.sh"))), "entry script byte for byte");
        check("in".equals(new String(Files.readAllBytes(dir.resolve("data/in.txt")), StandardCharsets.UTF_8)), "other attachment");
    }

    static void resultFileBeforeInit() {
        String vm = VmScriptFactory.createCaseScript("/home/u/CUBRID", "/home/u/CTP", "/home/u/tc/shell/x/cases", "x.sh",
            "22", "", "", "", "/tmp/pgid");
        int export = vm.indexOf("export result_file=.result_before_init");
        int run = vm.indexOf("sh \"$CASE_FILE\"");
        check(export > 0 && export < run, "VM: result_file set before the case runs");
        check(vm.indexOf("rm -f \"$CASE_DIR/.result_before_init\"") > run, "VM: scratch removed after the case");
        check(vm.contains("case exit code: $CASE_EXIT"), "VM: case exit code still reported");

        String docker = EnvScriptFactory.createDockerOptimizedScript("x.sh", "x", null, "shell/x/cases", "/workspace/CTP", null);
        export = docker.indexOf("export result_file=.result_before_init");
        run = docker.indexOf("sh x.sh");
        check(export > 0 && export < run && docker.indexOf("rm -f .result_before_init") > run, "Docker: same around the case");
        check(docker.indexOf("safe.directory") > 0 && docker.indexOf("safe.directory") < run, "Docker: git allowed in the checkout");
    }

    /**
     * VM mode puts the request's CTP into the VM's CTP, all but conf (as CTP's upgrade.sh): a VM
     * CTP copied once lacked wait_until, which Docker cases (provisioned CTP) had
     * (req_20261027_132503_4fc4).
     */
    static void vmCtpInstalled(Path root) throws Exception {
        Path ctpRepo = root.resolve("ctp_repo");
        git(root, "init", "-q", ctpRepo.toString());
        for (String dir : new String[]{"bin", "common", "sql", "isolation"}) {
            commit(ctpRepo, "CTP/" + dir + "/README", dir);
        }
        commit(ctpRepo, "CTP/conf/sql.conf", "upstream sql.conf");
        commit(ctpRepo, "CTP/shell/init_path/init.sh", "function init { :; }\nfunction wait_until { :; }\n");
        Path ctpSh = ctpRepo.resolve("CTP/bin/ctp.sh");
        Files.write(ctpSh, "#!/bin/bash\n".getBytes(StandardCharsets.UTF_8));
        ctpSh.toFile().setExecutable(true);
        git(ctpRepo, "add", "CTP/bin/ctp.sh");
        git(ctpRepo, "commit", "-q", "-m", "ctp.sh");
        String sha = git(ctpRepo, "rev-parse", "HEAD");

        Path vmCtp = root.resolve("vm_home/CTP");
        Files.createDirectories(vmCtp.resolve("shell/init_path"));
        Files.createDirectories(vmCtp.resolve("conf"));
        Files.write(vmCtp.resolve("shell/init_path/init.sh"), "function init { :; }\n".getBytes(StandardCharsets.UTF_8));
        Files.write(vmCtp.resolve("conf/sql.conf"), "the VM's sql.conf".getBytes(StandardCharsets.UTF_8));
        Files.write(vmCtp.resolve("conf/common.conf"), "git_user=vm".getBytes(StandardCharsets.UTF_8));
        Files.write(vmCtp.resolve("shell/init_path/vm_only.sh"), "x".getBytes(StandardCharsets.UTF_8));

        Path conf = root.resolve("vm_tester.conf");
        Files.write(conf, ("cubrid_src_dir=" + root + "\nshell_tc_dir=" + root + "\nwork_dir=" + root.resolve("vm_work")
            + "\nexecution_mode=vm\nvm_ctp_home=" + vmCtp + "\nctp_sql_repo=" + ctpRepo + "\nctp_sql_ref=master\n")
            .getBytes(StandardCharsets.UTF_8));
        com.navercorp.cubridqa.builder.config.Config config = new com.navercorp.cubridqa.builder.config.Config(conf.toString());
        com.navercorp.cubridqa.builder.ctp.CtpProvisioner provisioner = new com.navercorp.cubridqa.builder.ctp.CtpProvisioner(config);
        TestRequest request = new TestRequest(new JSONObject().put("testPath", "shell/x/cases/x.sh")
            .put("buildPackage", "p").put("ctpSqlBaseSha", sha));
        Class<?> vmCtpClass = Class.forName("com.navercorp.cubridqa.builder.exec.VmCtp");
        java.lang.reflect.Method install = vmCtpClass.getDeclaredMethod("ensureInstalled",
            com.navercorp.cubridqa.builder.config.Config.class, com.navercorp.cubridqa.builder.ctp.CtpProvisioner.class,
            TestRequest.class, Path.class, StringBuilder.class, Logger.class);
        install.setAccessible(true);

        StringBuilder preamble = new StringBuilder();
        install.invoke(null, config, provisioner, request, vmCtp, preamble, LOG);
        String initSh = new String(Files.readAllBytes(vmCtp.resolve("shell/init_path/init.sh")), StandardCharsets.UTF_8);
        check(initSh.contains("wait_until"), "the request's init.sh (wait_until) is in the VM's CTP: " + preamble);
        check("the VM's sql.conf".equals(new String(Files.readAllBytes(vmCtp.resolve("conf/sql.conf")), StandardCharsets.UTF_8)),
            "conf stays the VM's own, as upgrade.sh leaves it");
        check(Files.exists(vmCtp.resolve("conf/common.conf")) && Files.exists(vmCtp.resolve("shell/init_path/vm_only.sh")),
            "the VM's own files stay");
        check(Files.isExecutable(vmCtp.resolve("bin/ctp.sh")), "scripts stay executable");
        StringBuilder again = new StringBuilder();
        install.invoke(null, config, provisioner, request, vmCtp, again, LOG);
        check(again.toString().contains("already"), "installed once per CTP commit: " + again);

        Files.write(conf, (new String(Files.readAllBytes(conf), StandardCharsets.UTF_8) + "vm_ctp_from_repo=false\n")
            .getBytes(StandardCharsets.UTF_8));
        Files.write(vmCtp.resolve("shell/init_path/init.sh"), "old".getBytes(StandardCharsets.UTF_8));
        install.invoke(null, new com.navercorp.cubridqa.builder.config.Config(conf.toString()), provisioner, request, vmCtp,
            new StringBuilder(), LOG);
        check("old".equals(new String(Files.readAllBytes(vmCtp.resolve("shell/init_path/init.sh")), StandardCharsets.UTF_8)),
            "vm_ctp_from_repo=false (consumer VMs) leaves the VM's CTP alone");
    }

    private static void commit(Path repo, String file, String content) throws Exception {
        Path path = repo.resolve(file);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        git(repo, "add", file);
        git(repo, "commit", "-q", "-m", "add " + file);
    }

    private static String git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_AUTHOR_NAME", "t");
        pb.environment().put("GIT_AUTHOR_EMAIL", "t@example.com");
        pb.environment().put("GIT_COMMITTER_NAME", "t");
        pb.environment().put("GIT_COMMITTER_EMAIL", "t@example.com");
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int n; (n = in.read(chunk)) > 0; ) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
        System.out.println("  ok: " + what);
    }
}
