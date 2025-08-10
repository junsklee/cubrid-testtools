# Usage

## Setup

Prerequisites:
- Java 8+
- Docker
- CUBRID source checkout (or network access for Builder to clone)
- Shell testcases repository
- `GITHUB_TOKEN` env var

Build:
```bash
cd CTP/builder_tester
./bin/compile.sh
```

Configure:
- `conf/builder.conf`
- `conf/tester.conf`

## Run services

Tester (on test machine):
```bash
export GITHUB_TOKEN=your_github_token
./bin/start_tester.sh
```

Builder:
```bash
export GITHUB_TOKEN=your_github_token
./bin/start_builder.sh
```

## Client request

Scripted:
```bash
./bin/test_client.sh
```

Manual:
```bash
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["0d7296a", "dd32812", "6ea587e", "228cd61"],
    "tests": ["shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh"],
    "callbackUrl": "http://localhost:8888/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
```

Check status and health:
```bash
curl http://localhost:8089/status
curl http://localhost:8090/health
```

## Direct Tester invocation

Synchronous run (returns PASS/FAIL):
```bash
curl -X POST http://localhost:8090/test \
  -H "Content-Type: application/json" \
  -d '{
    "buildPackage": "/path/to/cubrid_xxx.tar.gz",
    "testPath": "shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh",
    "testDir": "/abs/path/to/.../cases",
    "testScript": "csql_hist.sh",
    "testName": "csql_hist",
    "expectedBuildVersion": "6ea587e",
    "keepAlive": false
  }'
```

Keep-alive (debug) run:
```bash
# keepAlive true returns container info
{
  "status": "started",
  "containerName": "tester_debug_<name>_<ts>",
  "execCommand": "docker exec -it tester_debug_<name>_<ts> bash",
  "workspace": "/tmp/tester_work/test_.../docker_..."
}
```

## Notes on isolated builds

- Baseline = parent of earliest commit in `commits[]`
- Docker build path (default): clone into writable target, checkout temp branch at baseline, cherry-pick only the target commit, `git submodule sync && git submodule update --init --recursive --checkout --force`, clean, build, package
- Direct host fallback: create temp branch + `git worktree add` at baseline, cherry-pick only the target commit, sync submodules, clean, build, package, remove worktree and delete temp branch
- Merge commits are cherry-picked with `-m 1`

## Logs

- System logs: `~/cubrid-testtools/CTP/builder_tester/log/system/{builder.log,tester.log}`
- Request logs: `~/cubrid-testtools/CTP/builder_tester/log/requests/req_*/{builder.log,builds/,tests/}`
- Metadata: `~/cubrid-testtools/CTP/builder_tester/log/.metadata.json`
- Service stdout: `bin/builder_output.log`, `bin/tester_output.log`

## Troubleshooting

- Docker permissions: `docker ps`
- Token: ensure `GITHUB_TOKEN` is set in the environment where services start
- Build issues: inspect `log/builds/*`
- Test issues: inspect `log/tests/*` and container logs `docker logs <container>`