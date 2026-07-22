package com.navercorp.cubridqa.builder.git;

import com.navercorp.cubridqa.builder.BuilderConfig;

/**
 * Shell testcases repository synchronizer (cubrid-testcases-private-ex).
 * All mechanics live in {@link TcRepoSync}; this class only binds the shell
 * repository settings from configuration.
 */
public class ShellTcSync extends TcRepoSync {

    public ShellTcSync(BuilderConfig config) {
        super(new RepoSpec(
            "shell testcases",
            config.getShellTcDir(),
            config.getShellTcBranch(),
            config.getShellTcPreferredRemote(),
            config.getShellTcSyncMode(),
            config.getShellTcSyncIntervalSeconds(),
            config.getShellTcRequestsRootDir(),
            config.isShellTcOverlayActive(),
            config.getShellTcSourceDir()
        ));
    }
}
