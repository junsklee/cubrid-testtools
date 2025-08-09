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
    "commits": ["6ea587e"],
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

## Logs

- `~/cubrid-testtools/CTP/builder_tester/log/{builder.log,tester.log,builds,tests}`
- `bin/builder_output.log` and `bin/tester_output.log`

## Troubleshooting

- Docker permissions: `docker ps`
- Token: ensure `GITHUB_TOKEN` is set in the environment where services start
- Build issues: inspect `log/builds/*`
- Test issues: inspect `log/tests/*` and container logs `docker logs <container>`