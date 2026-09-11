#!/system/bin/sh
# AccountGuard — boot-completed.sh
# Runs after Android has fully booted.
# Sends the accounts-changed broadcast to flush AccountManagerService cache.

# This ensures any in-memory account cache in system_server picks up
# the visibility changes we made in post-fs-data.sh

sleep 5  # Give system_server time to fully initialize

# Send accounts changed broadcast to flush AccountManagerService cache
am broadcast --user 0 -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION 2>/dev/null

# Log
LOG_FILE="/data/adb/ksu/module_configs/accountguard-boot/boot.log"
echo "[$(date '+%H:%M:%S')] Cache flush broadcast sent" >> "$LOG_FILE"

exit 0
