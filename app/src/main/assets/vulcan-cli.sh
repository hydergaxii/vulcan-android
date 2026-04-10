#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# VULCAN CLI — Command-line interface for Vulcan
#
# Run via ADB:  adb shell run-as com.vulcan.app ./vulcan
# Or symlink:   adb shell "ln -s /data/data/com.vulcan.app/vulcan /data/local/tmp/vulcan"
#
# The CLI communicates with VulcanCoreService via a local UNIX socket.
# Every command is JSON in, JSON out.
# ─────────────────────────────────────────────────────────────────────────────

VULCAN_SOCKET="/data/data/com.vulcan.app/vulcan.sock"
VULCAN_VERSION="2.0.0"
VULCAN_ORANGE='\033[38;5;208m'
VULCAN_GREEN='\033[0;32m'
VULCAN_RED='\033[0;31m'
VULCAN_YELLOW='\033[0;33m'
VULCAN_GRAY='\033[0;37m'
RESET='\033[0m'
BOLD='\033[1m'

# ─── HELPERS ──────────────────────────────────────────────────────────────────

vulcan_send() {
    echo "$1" | nc -U "$VULCAN_SOCKET" 2>/dev/null || {
        echo -e "${VULCAN_RED}Error: Vulcan service not running. Open the Vulcan app first.${RESET}"
        exit 1
    }
}

print_header() {
    echo -e "${VULCAN_ORANGE}${BOLD}🔥 Vulcan${RESET} ${VULCAN_GRAY}v${VULCAN_VERSION}${RESET}"
    echo -e "${VULCAN_GRAY}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
}

usage() {
    print_header
    echo ""
    echo -e "${BOLD}USAGE:${RESET}  vulcan <command> [args]"
    echo ""
    echo -e "${BOLD}APP MANAGEMENT:${RESET}"
    echo "  list                     List all installed apps with status"
    echo "  install <appId>          Install app from Vulcan Store"
    echo "  remove  <appId>          Remove app and its data"
    echo "  start   <appId>          Start an app"
    echo "  stop    <appId>          Stop an app"
    echo "  restart <appId>          Restart an app"
    echo ""
    echo -e "${BOLD}LOGS:${RESET}"
    echo "  logs <appId>             View last 100 log lines"
    echo "  logs <appId> -f          Follow logs in real-time"
    echo ""
    echo -e "${BOLD}ENVIRONMENT:${RESET}"
    echo "  env get  <appId> <KEY>   Get env value"
    echo "  env set  <appId> <KEY> <VALUE>   Set env value"
    echo "  env list <appId>         List all env vars for an app"
    echo ""
    echo -e "${BOLD}BACKUP:${RESET}"
    echo "  backup  <appId>          Create manual backup"
    echo "  restore <appId> <file>   Restore from backup"
    echo ""
    echo -e "${BOLD}RUNTIME:${RESET}"
    echo "  runtime list             List installed runtimes"
    echo "  runtime install <engine> <version>   Install a runtime"
    echo ""
    echo -e "${BOLD}SYSTEM:${RESET}"
    echo "  status                   System status: RAM, CPU, running apps"
    echo "  version                  Vulcan version info"
    echo ""
}

# ─── COMMANDS ─────────────────────────────────────────────────────────────────

cmd_list() {
    print_header
    local result
    result=$(vulcan_send '{"cmd":"list"}')
    echo -e "${BOLD}Installed Apps:${RESET}"
    echo "$result" | python3 -c "
import json, sys
data = json.load(sys.stdin)
apps = data.get('apps', [])
if not apps:
    print('  No apps installed. Use: vulcan install <appId>')
    sys.exit(0)
for app in apps:
    status = app.get('status', 'stopped')
    color = '\033[0;32m' if status == 'running' else '\033[0;37m'
    dot = '●' if status == 'running' else '○'
    print(f'  {color}{dot}\033[0m  {app[\"id\"]:<20} {status:<10} port {app.get(\"port\", \"?\"):<6} v{app.get(\"version\", \"?\")}')
" 2>/dev/null || echo "$result"
}

cmd_start() {
    [ -z "$1" ] && { echo "Usage: vulcan start <appId>"; exit 1; }
    echo -e "${VULCAN_ORANGE}Starting $1...${RESET}"
    vulcan_send "{\"cmd\":\"start\",\"appId\":\"$1\"}"
    echo -e "${VULCAN_GREEN}✓ $1 start requested${RESET}"
    echo "  Run 'vulcan logs $1 -f' to watch startup"
}

cmd_stop() {
    [ -z "$1" ] && { echo "Usage: vulcan stop <appId>"; exit 1; }
    echo -e "${VULCAN_YELLOW}Stopping $1...${RESET}"
    vulcan_send "{\"cmd\":\"stop\",\"appId\":\"$1\"}"
    echo -e "${VULCAN_GREEN}✓ $1 stopped${RESET}"
}

cmd_restart() {
    [ -z "$1" ] && { echo "Usage: vulcan restart <appId>"; exit 1; }
    cmd_stop "$1"
    sleep 2
    cmd_start "$1"
}

cmd_install() {
    [ -z "$1" ] && { echo "Usage: vulcan install <appId>"; exit 1; }
    echo -e "${VULCAN_ORANGE}Installing $1...${RESET}"
    vulcan_send "{\"cmd\":\"install\",\"appId\":\"$1\"}" | while IFS= read -r line; do
        echo "  $line"
    done
}

cmd_remove() {
    [ -z "$1" ] && { echo "Usage: vulcan remove <appId>"; exit 1; }
    echo -e "${VULCAN_RED}Removing $1 (this cannot be undone)...${RESET}"
    read -r -p "Are you sure? (y/N) " confirm
    [ "$confirm" != "y" ] && { echo "Aborted."; exit 0; }
    vulcan_send "{\"cmd\":\"remove\",\"appId\":\"$1\"}"
    echo -e "${VULCAN_GREEN}✓ $1 removed${RESET}"
}

cmd_logs() {
    [ -z "$1" ] && { echo "Usage: vulcan logs <appId> [-f]"; exit 1; }
    local appId="$1"
    local follow="$2"
    local log_dir="/sdcard/Vulcan/apps/$appId/logs"
    local today
    today=$(date +%Y-%m-%d)
    local log_file="$log_dir/$today.log"

    if [ "$follow" = "-f" ]; then
        echo -e "${VULCAN_ORANGE}Following $appId logs (Ctrl+C to stop)...${RESET}"
        tail -f "$log_file" 2>/dev/null || echo "Log file not found: $log_file"
    else
        echo -e "${VULCAN_ORANGE}Last 100 lines from $appId:${RESET}"
        tail -100 "$log_file" 2>/dev/null || echo "Log file not found: $log_file"
    fi
}

cmd_env() {
    local subcmd="$1"
    local appId="$2"
    case "$subcmd" in
        get)
            [ -z "$3" ] && { echo "Usage: vulcan env get <appId> <KEY>"; exit 1; }
            local key="$3"
            grep "^$key=" "/sdcard/Vulcan/apps/$appId/.env" 2>/dev/null | cut -d= -f2-
            ;;
        set)
            [ -z "$4" ] && { echo "Usage: vulcan env set <appId> <KEY> <VALUE>"; exit 1; }
            local key="$3" value="$4"
            vulcan_send "{\"cmd\":\"env_set\",\"appId\":\"$appId\",\"key\":\"$key\",\"value\":\"$value\"}"
            echo -e "${VULCAN_GREEN}✓ Set $key for $appId${RESET}"
            ;;
        list)
            echo -e "${VULCAN_ORANGE}Env vars for $appId:${RESET}"
            cat "/sdcard/Vulcan/apps/$appId/.env" 2>/dev/null | grep -v '^#' | grep -v '^$'
            ;;
        *)
            echo "Usage: vulcan env <get|set|list> <appId> [key] [value]"
            ;;
    esac
}

cmd_backup() {
    [ -z "$1" ] && { echo "Usage: vulcan backup <appId>"; exit 1; }
    echo -e "${VULCAN_ORANGE}Creating backup for $1...${RESET}"
    vulcan_send "{\"cmd\":\"backup\",\"appId\":\"$1\"}"
    echo -e "${VULCAN_GREEN}✓ Backup created in /sdcard/Vulcan/backups/${RESET}"
}

cmd_restore() {
    [ -z "$2" ] && { echo "Usage: vulcan restore <appId> <backup_file>"; exit 1; }
    echo -e "${VULCAN_YELLOW}Restoring $1 from $2...${RESET}"
    vulcan_send "{\"cmd\":\"restore\",\"appId\":\"$1\",\"file\":\"$2\"}"
    echo -e "${VULCAN_GREEN}✓ $1 restored${RESET}"
}

cmd_runtime() {
    local subcmd="$1"
    case "$subcmd" in
        list)
            echo -e "${VULCAN_ORANGE}Installed runtimes:${RESET}"
            ls "/sdcard/Vulcan/runtimes/native/" 2>/dev/null | while read -r engine; do
                ls "/sdcard/Vulcan/runtimes/native/$engine/" 2>/dev/null | while read -r version; do
                    echo "  $engine @ $version"
                done
            done
            echo -e "${VULCAN_ORANGE}PRoot distros:${RESET}"
            ls "/sdcard/Vulcan/runtimes/proot/distros/" 2>/dev/null | while read -r distro; do
                echo "  $distro"
            done
            ;;
        install)
            [ -z "$3" ] && { echo "Usage: vulcan runtime install <engine> <version>"; exit 1; }
            echo -e "${VULCAN_ORANGE}Installing $2 @ $3...${RESET}"
            vulcan_send "{\"cmd\":\"runtime_install\",\"engine\":\"$2\",\"version\":\"$3\"}"
            ;;
        *)
            echo "Usage: vulcan runtime <list|install>"
            ;;
    esac
}

cmd_status() {
    print_header
    local result
    result=$(vulcan_send '{"cmd":"status"}')
    echo "$result" | python3 -c "
import json, sys
d = json.load(sys.stdin)
dm = d.get('device', {})
apps = d.get('runningApps', [])
total_ram = dm.get('totalRamMB', 0)
avail_ram = dm.get('availableRamMB', 0)
used_ram  = total_ram - avail_ram
cpu       = dm.get('cpuPercent', 0)
storage_t = dm.get('storageTotalMB', 0)
storage_u = dm.get('storageUsedMB', 0)
print(f'RAM:     {used_ram} MB / {total_ram} MB ({int(used_ram/total_ram*100) if total_ram else 0}%)')
print(f'CPU:     {cpu:.1f}% avg')
print(f'Storage: {storage_u//1024} GB / {storage_t//1024} GB')
print('\033[0;37m' + '━'*40 + '\033[0m')
if apps:
    print(f'Running Apps ({len(apps)}):')
    for app in apps:
        ram = app.get('ramMB', 0)
        cpu = app.get('cpuPercent', 0)
        print(f'  \033[0;32m●\033[0m {app[\"id\"]:<20} port {app.get(\"port\",\"?\"):<6} {ram:.0f} MB RAM   {cpu:.1f}% CPU')
else:
    print('No apps running.')
" 2>/dev/null || echo "$result"
}

cmd_version() {
    echo -e "${VULCAN_ORANGE}${BOLD}Vulcan v${VULCAN_VERSION}${RESET}"
    echo "The Greatest Self-Hosting Platform for a Pocket Device."
    echo "https://vulcan.app"
}

# ─── ENTRYPOINT ───────────────────────────────────────────────────────────────

COMMAND="$1"
shift

case "$COMMAND" in
    list)          cmd_list ;;
    start)         cmd_start "$@" ;;
    stop)          cmd_stop "$@" ;;
    restart)       cmd_restart "$@" ;;
    install)       cmd_install "$@" ;;
    remove|rm)     cmd_remove "$@" ;;
    logs)          cmd_logs "$@" ;;
    env)           cmd_env "$@" ;;
    backup)        cmd_backup "$@" ;;
    restore)       cmd_restore "$@" ;;
    runtime)       cmd_runtime "$@" ;;
    status)        cmd_status ;;
    version|-v|--version) cmd_version ;;
    help|-h|--help|"")    usage ;;
    *)
        echo -e "${VULCAN_RED}Unknown command: $COMMAND${RESET}"
        echo "Run 'vulcan help' for usage."
        exit 1
        ;;
esac
