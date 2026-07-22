package com.navercorp.cubridqa.builder.git;

import com.navercorp.cubridqa.builder.BuilderConfig;

/**
 * SQL testcases repository synchronizer (CUBRID/cubrid-testcases).
 * All mechanics live in {@link TcRepoSync}; this class only binds the SQL
 * repository settings from configuration.
 */
public class SqlTcSync extends TcRepoSync {

    public SqlTcSync(BuilderConfig config) {
        super(new RepoSpec(
            "sql testcases",
            config.getSqlTcDir(),
            config.getSqlTcBranch(),
            config.getSqlTcPreferredRemote(),
            config.getSqlTcSyncMode(),
            config.getSqlTcSyncIntervalSeconds(),
            config.getSqlTcRequestsRootDir(),
            false,
            config.getSqlTcDir()
        ));
    }
}
