#!/system/bin/sh
# AccountGuard KSU Boot Service — post-fs-data.sh
# Runs early in boot (before user space), after /data is mounted.
# Reads policy DB and re-applies visibility entries to accounts_de.db.
#
# SAFETY: This script ONLY modifies the visibility table.
#         It NEVER modifies the accounts table.
#         Idempotent: re-applying same policy has no side effects.

MODDIR="${0%/*}"
POLICY_DB="/data/data/dev.accountguard/databases/accountguard_policy.db"
USER_ID=0
ACCOUNTS_DE="/data/system_de/${USER_ID}/accounts_de.db"
ACCOUNTS_CE="/data/system_ce/${USER_ID}/accounts_ce.db"
LOG_FILE="/data/adb/ksu/module_configs/accountguard-boot/boot.log"

log() {
    echo "[$(date '+%H:%M:%S')] $1" >> "$LOG_FILE"
}

# Create log directory
mkdir -p "$(dirname "$LOG_FILE")"
log "AccountGuard boot service started"

# ── WAIT FOR DB TO BE ACCESSIBLE ──
# accounts_de.db is available after /data is mounted (which it is at post-fs-data)
# but sqlite3 binary may need a moment
RETRIES=0
while [ $RETRIES -lt 5 ]; do
    if [ -f "$ACCOUNTS_DE" ]; then
        break
    fi
    sleep 1
    RETRIES=$((RETRIES + 1))
done

if [ ! -f "$ACCOUNTS_DE" ]; then
    log "ERROR: accounts_de.db not found at $ACCOUNTS_DE"
    exit 1
fi

log "accounts_de.db found"

# ── WAIT FOR POLICY DB ──
# The policy DB is in /data/data which is available at post-fs-data
if [ ! -f "$POLICY_DB" ]; then
    log "Policy DB not found yet — no policies to apply on this boot"
    exit 0
fi

log "Policy DB found at $POLICY_DB"

# ── RE-APPLY ALL POLICIES ──
# Query policy DB for all HIDDEN policies (visibility_state = 4)
# Then apply each one to accounts_de.db

POLICY_COUNT=0

# Get all hidden policies: account_name, target_package, visibility_state
sqlite3 "$POLICY_DB" "SELECT account_name, target_package, visibility_state FROM policies WHERE visibility_state = 4;" 2>/dev/null | while IFS='|' read -r account_name target_package visibility_state; do
    # Get account DB ID from accounts_de.db
    ACCOUNT_ID=$(sqlite3 "$ACCOUNTS_DE" "SELECT _id FROM accounts WHERE name='${account_name}' AND type='com.google' LIMIT 1;" 2>/dev/null)

    if [ -z "$ACCOUNT_ID" ]; then
        log "WARN: Account '$account_name' not found in accounts_de.db — skipping"
        continue
    fi

    # Handle special targets
    case "$target_package" in
        "__THIRD_PARTY__")
            PKG_KEY="__PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE__"
            ;;
        "__ACCOUNT_PICKER__")
            PKG_KEY="android"
            ;;
        "__SYNC__")
            # Sync will be re-applied by the app; skip in boot script
            log "SKIP: Sync policy for $account_name (handled by app)"
            continue
            ;;
        *)
            PKG_KEY="$target_package"
            ;;
    esac

    # Apply visibility to accounts_de.db
    sqlite3 "$ACCOUNTS_DE" \
        "INSERT OR REPLACE INTO visibility (accounts_id, package_name, visibility) VALUES (${ACCOUNT_ID}, '${PKG_KEY}', ${visibility_state});" \
        2>/dev/null

    if [ $? -eq 0 ]; then
        log "APPLIED: $account_name → $PKG_KEY = $visibility_state"
        POLICY_COUNT=$((POLICY_COUNT + 1))
    else
        log "FAILED: $account_name → $PKG_KEY"
    fi

    # Also apply to CE db (may fail if not unlocked yet — that's fine)
    sqlite3 "$ACCOUNTS_CE" \
        "INSERT OR REPLACE INTO visibility (accounts_id, package_name, visibility) VALUES (${ACCOUNT_ID}, '${PKG_KEY}', ${visibility_state});" \
        2>/dev/null
done

log "Boot service complete — policies applied"
exit 0
