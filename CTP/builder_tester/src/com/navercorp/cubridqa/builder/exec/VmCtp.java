package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.ctp.CtpProvisioner;
import com.navercorp.cubridqa.builder.tester.TestRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;

/**
 * Puts the request's CTP (the commit of ctp_sql_repo the Builder pinned for it, as Docker cases
 * get) into the VM's CTP, as regression keeps a VM's CTP current: CTP's upgrade.sh updates every
 * directory but conf from the repository. A VM CTP copied once and never updated lacked what cases
 * written for the current init.sh call (wait_until: command not found), while Docker had it.
 */
final class VmCtp {
    /** The payload directories copied over the VM's CTP; conf stays the VM's own, as upgrade.sh leaves it. */
    private static final String[] INSTALLED_DIRS = {"bin", "common", "sql", "shell", "isolation"};
    private static final String MARKER = ".builder_tester_ctp";

    private VmCtp() {}

    /**
     * Installs the request's CTP into ctpHome unless it is there already (marker) or vm_ctp_from_repo
     * is off. Call it holding VM_LOCK. A failure leaves the VM's CTP as it was; it is noted in preamble.
     */
    static void ensureInstalled(Config config, CtpProvisioner provisioner, TestRequest request, Path ctpHome,
                                StringBuilder preamble, Logger log) {
        if (provisioner == null || !config.isVmCtpFromRepo()) {
            return;
        }
        try {
            CtpProvisioner.CtpPayload payload = provisioner.preparePayload(request.getCtpSqlBaseSha(), log);
            Path marker = ctpHome.resolve(MARKER);
            if (Files.isRegularFile(marker)
                    && payload.baseSha.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim())) {
                preamble.append("[builder-tester vm] CTP ").append(payload.fingerprint).append(" (").append(config.getCtpSqlRepo())
                    .append(") already in ").append(ctpHome).append('\n');
                return;
            }
            log.info("Installing CTP " + payload.fingerprint + " into " + ctpHome + " (all but conf)");
            for (String dir : INSTALLED_DIRS) {
                Path source = payload.ctpDir.resolve(dir);
                if (Files.isDirectory(source)) {
                    copyOver(source, ctpHome.resolve(dir));
                }
            }
            Files.write(marker, (payload.baseSha + "\n").getBytes(StandardCharsets.UTF_8));
            preamble.append("[builder-tester vm] installed CTP ").append(payload.fingerprint).append(" (")
                .append(config.getCtpSqlRepo()).append(") into ").append(ctpHome).append(", conf kept\n");
        } catch (Exception e) {
            log.warning("Could not install the request's CTP into " + ctpHome + ": " + e.getMessage());
            preamble.append("[builder-tester vm] WARNING: could not install CTP ")
                .append(request.getCtpSqlBaseSha() != null ? request.getCtpSqlBaseSha() : config.getCtpSqlRef())
                .append(" (").append(e.getMessage()).append("); the case runs with the VM's CTP as it is\n");
        }
    }

    /** Copies source over target: files replaced with their permissions (scripts stay executable), others kept. */
    private static void copyOver(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
