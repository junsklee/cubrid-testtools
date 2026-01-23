# Server Monitoring and Auto-Restart Guide

This guide explains how to set up automatic monitoring and restart for the CUBRID Test Report Server.

## Overview

The monitoring system consists of:
1. **Enhanced server stability** - Improved error handling, connection management, and health checks
2. **Monitor script** - `monitor-server.sh` that checks server health and restarts if needed
3. **Cron job** - Automatically runs the monitor every 5 minutes

## Monitor Script Usage

The `monitor-server.sh` script provides several modes of operation:

### Check Once
```bash
./monitor-server.sh once
```
Checks server health once and exits. Useful for manual checks or cron jobs.

### Continuous Loop
```bash
./monitor-server.sh loop
```
Continuously monitors the server, checking every 5 minutes. Runs indefinitely until stopped.

### Manual Control
```bash
./monitor-server.sh start    # Start the server
./monitor-server.sh stop     # Stop the server
./monitor-server.sh restart  # Restart the server
./monitor-server.sh status   # Check server status
```

## Setting Up Cron Job

### Option 1: Using Crontab (Recommended)

1. Open your crontab:
```bash
crontab -e
```

2. Add the following line to check every 5 minutes:
```bash
*/5 * * * * /home/jun2/cubrid-testtools/CTP/builder_tester/report-server/monitor-server.sh once >> /home/jun2/cubrid-testtools/CTP/builder_tester/report-server/monitor-cron.log 2>&1
```

3. Save and exit. The cron job will be active immediately.

### Option 2: Using Systemd Timer (Alternative)

Create a systemd service file at `/etc/systemd/system/report-server-monitor.service`:

```ini
[Unit]
Description=CUBRID Report Server Monitor
After=network.target

[Service]
Type=oneshot
User=jun2
WorkingDirectory=/home/jun2/cubrid-testtools/CTP/builder_tester/report-server
ExecStart=/home/jun2/cubrid-testtools/CTP/builder_tester/report-server/monitor-server.sh once
StandardOutput=append:/home/jun2/cubrid-testtools/CTP/builder_tester/report-server/monitor-cron.log
StandardError=append:/home/jun2/cubrid-testtools/CTP/builder_tester/report-server/monitor-cron.log
```

Create a timer file at `/etc/systemd/system/report-server-monitor.timer`:

```ini
[Unit]
Description=Run Report Server Monitor every 5 minutes
Requires=report-server-monitor.service

[Timer]
OnBootSec=1min
OnUnitActiveSec=5min
Unit=report-server-monitor.service

[Install]
WantedBy=timers.target
```

Enable and start:
```bash
sudo systemctl enable report-server-monitor.timer
sudo systemctl start report-server-monitor.timer
```

### Option 3: Run Monitor in Loop Mode

You can also run the monitor script in loop mode as a background process:

```bash
nohup ./monitor-server.sh loop > monitor-loop.log 2>&1 &
```

Or use a process manager like `pm2`:
```bash
pm2 start monitor-server.sh --name "server-monitor" --interpreter bash -- loop
pm2 save
pm2 startup
```

## Configuration

The monitor script uses the following environment variables (with defaults):

- `REPORT_PORT` - Server port (default: 8091)
- `HOST` - Server host (default: localhost)

You can set these in your crontab or systemd service:
```bash
*/5 * * * * export REPORT_PORT=8091 && /path/to/monitor-server.sh once
```

## Logs

The monitor script logs to:
- `monitor.log` - General monitor activity
- `server.log` - Server output (when started by monitor)
- `monitor-cron.log` - Cron output (if using cron)
- `.monitor-state` - Persists restart counters/timestamps across runs

## Health Check

The monitor checks server health by:
1. Verifying the server process is running
2. Making an HTTP request to `/health` endpoint
3. Checking for a `"status":"healthy"` response

If either check fails, the server is restarted.

## Restart Protection

The monitor includes protection against restart loops:
- Enforces a 60-second minimum between restart attempts
- Stops auto-restarting after 3 failed restart attempts (persists across cron runs via `.monitor-state`)
- Requires manual intervention if the server keeps failing to start/respond

## Troubleshooting

### Server won't start
- Check `server.log` for error messages
- Verify port is not already in use: `lsof -i :8091`
- Check file permissions on the script

### Monitor not running
- Check cron logs: `tail -f /var/log/cron` (Linux) or check mail
- Verify script is executable: `chmod +x monitor-server.sh`
- Test manually: `./monitor-server.sh once`

### Server keeps restarting
- Check `server.log` for recurring errors
- Review `monitor.log` for restart reasons
- Verify server dependencies are available

## Manual Testing

Test the monitor script:
```bash
# Check current status
./monitor-server.sh status

# Stop server manually
./monitor-server.sh stop

# Run monitor (should detect and restart)
./monitor-server.sh once

# Check status again
./monitor-server.sh status
```
