package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.docker.DockerImageBuilder;

import java.net.URL;

/**
 * Release and debug builds (or other baselines) of one commit must not share a tester image or
 * a cached package, while one build must map to the same image whether the tester got it as a
 * local path or a download URL.
 */
public class BuildIdentityTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BuildIdentityTest Suite ===\n");

        String local = "/home/qa/tmp/builder_work/build_abc1234_1700000000000/cubrid_abc1234.tar.gz";
        String remote = "http://10.0.0.5:8089/download/build/build_abc1234_1700000000000/cubrid_abc1234.tar.gz";
        String debug = "http://10.0.0.5:8089/download/build/build_abc1234_1700000999999/cubrid_abc1234.tar.gz";

        String a = DockerImageBuilder.imageVariant("history", "release", local);
        String b = DockerImageBuilder.imageVariant("history", "release", remote);
        String c = DockerImageBuilder.imageVariant("history", "debug", debug);
        String d = DockerImageBuilder.imageVariant("history", "debug", local);
        check(a.equals(b), "same build via path and URL shares an image: " + a);
        check(!a.equals(c), "debug build gets its own image: " + c);
        check(!a.equals(d), "build type alone separates images: " + d);
        check(a.matches("[A-Za-z0-9_.-]+"), "variant is a valid docker tag fragment");
        check(DockerImageBuilder.buildDirectoryOf("/x/cubrid.tar.gz") == null, "no build dir -> null");

        // The base image is part of the image's identity: switching docker_test_image (or the tag
        // being republished) must not reuse an image provisioned on the previous base.
        String shellBase = "sha256:c4d9a0986b60aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String rl810Base = "sha256:017c06130cebbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String k1 = DockerImageBuilder.imageKey("abc1234", a, shellBase);
        String k2 = DockerImageBuilder.imageKey("abc1234", a, rl810Base);
        check(!k1.equals(k2), "another base image gets its own image: " + k2);
        check(k1.equals(DockerImageBuilder.imageKey("abc1234", a, shellBase)), "same base, same image");
        check(k2.equals("abc1234_" + a + "_017c06130ceb"), "key ends with the base image id: " + k2);
        check(!k1.equals("abc1234_" + a), "never the pre-base-id name an old image may still carry");
        check(k1.matches("[A-Za-z0-9_.-]+") && k1.length() <= 128, "key is a valid docker tag");
        check(DockerImageBuilder.imageKey("abc1234", a, "017c06130ceb").equals(k2), "bare id without the sha256: prefix");
        boolean refused = false;
        try {
            DockerImageBuilder.imageKey("abc1234", a, "");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "no base id -> refused, never a name without one");

        String n1 = BuildCache.localFileName(new URL(remote));
        String n2 = BuildCache.localFileName(new URL(debug));
        check(n1.equals("build_abc1234_1700000000000__cubrid_abc1234.tar.gz"), "cache name includes build dir: " + n1);
        check(!n1.equals(n2), "two builds of a commit cache to different files");
        check("cubrid_abc1234.tar.gz".equals(BuildCache.localFileName(new URL("http://h:8089/download/build/cubrid_abc1234.tar.gz"))),
            "legacy URL keeps its file name");

        System.out.println("\n=== All BuildIdentityTest tests passed! ===");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
        System.out.println("  ok  " + label);
    }
}
