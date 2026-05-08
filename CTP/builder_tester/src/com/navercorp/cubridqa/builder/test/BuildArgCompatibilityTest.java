package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.DockerBuildManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.List;

public class BuildArgCompatibilityTest {

    public static void main(String[] args) throws Exception {
        testGeneratorFlagKeptWhenSupported();
        testGeneratorFlagRemovedWhenUnsupported();
        testMissingBuildScriptKeepsConfiguredArgs();
        testHelpTextDetection();
        System.out.println("BuildArgCompatibilityTest passed");
    }

    private static void testGeneratorFlagKeptWhenSupported() throws Exception {
        File sourceDir = createSourceDirWithBuildHelp(
                "Usage: ./build.sh [OPTIONS] [TARGET]\n" +
                " OPTIONS\n" +
                "  -g      Specifies the generator for a build (make, ninja); [default: ninja]\n");

        List<String> args = DockerBuildManager.resolveBuildArgsForScript(
                sourceDir, "-g ninja -c -DENABLE_SYSTEMTAP=OFF build", "release");

        assert args.contains("-g") : "Expected -g to remain for new build.sh";
        assert args.contains("ninja") : "Expected generator value to remain for new build.sh";
        assert args.contains("-m") : "Expected normalized -m option";
        assert args.contains("release") : "Expected normalized release mode";
    }

    private static void testGeneratorFlagRemovedWhenUnsupported() throws Exception {
        File sourceDir = createSourceDirWithBuildHelp(
                "Usage: ./build.sh [OPTIONS] [TARGET]\n" +
                " OPTIONS\n" +
                "  -c opts Set configure options; [default: NONE]\n");

        List<String> args = DockerBuildManager.resolveBuildArgsForScript(
                sourceDir, "-g ninja -c -DENABLE_SYSTEMTAP=OFF build", "release");

        assert !args.contains("-g") : "Expected -g to be removed for old build.sh";
        assert !args.contains("ninja") : "Expected generator value to be removed for old build.sh";
        assert args.contains("-c") : "Expected configure option to remain";
        assert args.contains("-DENABLE_SYSTEMTAP=OFF") : "Expected configure value to remain";
        assert args.contains("build") : "Expected build target to remain";
    }

    private static void testMissingBuildScriptKeepsConfiguredArgs() throws Exception {
        File sourceDir = Files.createTempDirectory("missing_build_sh_").toFile();

        List<String> args = DockerBuildManager.resolveBuildArgsForScript(
                sourceDir, "-g ninja -c -DENABLE_SYSTEMTAP=OFF build", "debug");

        assert args.contains("-g") : "Expected -g to remain when build.sh cannot be inspected";
        assert args.contains("ninja") : "Expected generator value to remain when build.sh cannot be inspected";
        assert args.contains("debug") : "Expected normalized debug mode";
    }

    private static void testHelpTextDetection() {
        assert DockerBuildManager.buildScriptHelpSupportsGeneratorOption("  -g      Specifies generator\n") :
                "Expected -g help line to be detected";
        assert !DockerBuildManager.buildScriptHelpSupportsGeneratorOption("  -c opts Set configure options\n") :
                "Did not expect -g support in legacy help";
    }

    private static File createSourceDirWithBuildHelp(String helpText) throws Exception {
        File sourceDir = Files.createTempDirectory("build_arg_compat_").toFile();
        File buildScript = new File(sourceDir, "build.sh");
        try (PrintWriter writer = new PrintWriter(new FileWriter(buildScript))) {
            writer.println("#!/bin/bash");
            writer.println("cat <<'EOF'");
            writer.print(helpText);
            writer.println("EOF");
            writer.println("exit 1");
        }
        buildScript.setExecutable(true);
        return sourceDir;
    }
}
