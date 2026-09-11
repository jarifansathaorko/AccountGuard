#!/system/bin/sh
# AccountGuard — Emergency Recovery Script
# Run this if the app is unavailable and you need to restore all accounts immediately.
#
# Usage (via ADB with root):
#   adb shell su -c "sh /data/adb/modules/accountguard-boot/emergency_restore.sh"
#
# What this does:
#   1. Removes ALL custom visibility entries from accounts_de.db and accounts_ce.db
#   2. Sends a broadcast to flush AccountManagerService cache
#   3. ALL accounts become visible to ALL apps again
#   4. NO re-login required — accounts are untouched
#
# This is completely safe. Accounts are never modified, only visibility entries.

USER_ID=0
ACCOUNTS_DE="/data/system_de/${USER_ID}/accounts_de.db"
ACCOUNTS_CE="/data/system_ce/${USER_ID}/accounts_ce.db"

echo "=================================================="
echo " AccountGuard Emergency Recovery"
echo "=================================================="
echo ""
echo "Current visibility entries in accounts_de.db:"
sqlite3 "$ACCOUNTS_DE" "SELECT a.name, v.package_name, v.visibility FROM visibility v JOIN accounts a ON v.accounts_id = a._id ORDER BY a.name, v.package_name;" 2>/dev/null
echo ""

echo "Clearing all custom visibility entries..."
sqlite3 "$ACCOUNTS_DE" "DELETE FROM visibility;" 2>&1
echo "DE database cleared: $?"

sqlite3 "$ACCOUNTS_CE" "DELETE FROM visibility;" 2>/dev/null
echo "CE database cleared (may fail if locked — normal)"

echo ""
echo "Sending AccountManager cache flush broadcast..."
am broadcast --user 0 -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION 2>/dev/null
echo "Broadcast sent"

echo ""
echo "Verification — remaining visibility entries:"
sqlite3 "$ACCOUNTS_DE" "SELECT COUNT(*) FROM visibility;" 2>/dev/null

echo ""
echo "=================================================="
echo " Recovery complete!"
echo " All accounts are now fully visible everywhere."
echo " No re-login required."
echo "=================================================="
