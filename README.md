# AccountGuard

> **Root-powered, per-account, per-target Google account visibility controller for Android 16**
> Xiaomi K50i (xaga) · Lunaris AOSP · KernelSU-Next · BreZygisk · Vector

---

## What It Does

AccountGuard lets you **temporarily hide any Google account from any app** — without logging out.  
When you un-hide it, no re-login is required. Your credentials, emails, and data are completely untouched.

**Example**: You have `work@gmail.com` and `personal@gmail.com` installed.  
You want YouTube to only show `personal@gmail.com`. Toggle `work@gmail.com → YouTube = OFF`.  
Done. Switch it back anytime — no re-login needed.

---

## Technical Architecture

### How It Actually Works

Android's `AccountManagerService` (in `system_server`) reads from a SQLite database:
```
/data/system_de/0/accounts_de.db
```

This database has a `visibility` table that maps `(account_id, package_name) → visibility_value`:
```sql
VISIBILITY_VISIBLE     = 1   -- app can see the account
VISIBILITY_NOT_VISIBLE = 4   -- app cannot see the account
```

AccountGuard writes `4` into this table for the `(account, target)` pairs you want to hide.  
The account remains installed. Credentials, tokens, sync state — all untouched.  
To restore: delete the row → account becomes visible again → no re-login.

### Coverage

| Target | Support | Mechanism |
|---|---|---|
| Gmail | ✅ SUPPORTED | DB visibility table |
| Play Store | ✅ SUPPORTED | DB visibility table |
| YouTube | ✅ SUPPORTED | DB visibility table |
| YouTube Studio | ✅ SUPPORTED | DB visibility table |
| Google Drive | ✅ SUPPORTED | DB visibility table |
| Google Photos | ✅ SUPPORTED | DB visibility table |
| Google Maps | ✅ SUPPORTED | DB visibility table |
| Google Calendar | ✅ SUPPORTED | DB visibility table |
| Google Meet | ✅ SUPPORTED | DB visibility table |
| Google Contacts | ✅ SUPPORTED | DB visibility table |
| Google Keep | ✅ SUPPORTED | DB visibility table |
| Google Tasks | ✅ SUPPORTED | DB visibility table |
| Google App | ✅ SUPPORTED | DB visibility table |
| All Other Apps | ✅ SUPPORTED | Legacy visibility key |
| Account Sync | ✅ SUPPORTED | ContentResolver sync API |
| Android Settings | ⚠️ EXPERIMENTAL | Requires Vector LSPosed |
| Account Pickers | ✅ SUPPORTED | DB visibility table |
| Google Play Services | ❌ UNSUPPORTED | GMS is the authenticator |
| Chrome | ❌ EXCLUDED | Separate sign-in state |

---

## Requirements

- **Root**: KernelSU-Next (tested) / Magisk compatible
- **Zygisk**: BreZygisk (for Vector support)
- **Android**: 16 (API 36) — tested on Lunaris AOSP 3.12
- **Device**: Xiaomi K50i (xaga, MT6895) — and compatible devices
- **Optional**: Vector (LSPosed fork) for Android Settings hiding

---

## Installation

### 1. Install the APK
Build the APK using Android Studio or:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Install the KSU Boot Module (optional but recommended)

This provides boot persistence — policies are re-applied automatically on every reboot.

```bash
# Install via KernelSU Manager
# Flash: ksu-module/AccountGuard-BootService-KSU-v1.zip
```

Or push manually:
```bash
adb push ksu-module/ /data/adb/modules/accountguard-boot/
adb shell su -c "chmod 755 /data/adb/modules/accountguard-boot/*.sh"
```

### 3. Launch AccountGuard
- Open AccountGuard
- Grant root access when prompted by KernelSU
- Tap an account → toggle targets OFF to hide

---

## Emergency Recovery

If something goes wrong, run this WITHOUT the app:

```bash
adb shell su -c "sh /data/adb/modules/accountguard-boot/emergency_restore.sh"
```

Or manually:
```bash
adb shell su -c "sqlite3 /data/system_de/0/accounts_de.db 'DELETE FROM visibility;'"
adb shell am broadcast -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION
```

**No re-login required after recovery.**

---

## Known Limitations

1. **Android Settings** requires Vector/LSPosed (system_server scope). Without it, Settings shows all accounts — the app will warn you if Vector is not detected.

2. **Google Play Services** always sees all accounts — it's the authenticator. This is correct and necessary for credentials to remain valid.

3. **Existing sessions** continue briefly after hiding (cached tokens expire naturally in 1–60 minutes). This is intentional non-destructive behavior.

4. **After OTA**: DB-based policies persist. Vector hooks may need updating post-OTA.

---

## Project Structure

```
AccountGuard/
├── app/
│   └── src/main/java/dev/accountguard/
│       ├── MainActivity.kt           # App + Navigation
│       ├── data/
│       │   ├── model/Models.kt       # Domain models
│       │   ├── model/TargetRegistry.kt # Target registry
│       │   └── db/PolicyDatabase.kt  # Room DB schema
│       ├── root/
│       │   └── RootEngine.kt         # All root operations (libsu)
│       ├── service/
│       │   ├── PolicyRepository.kt   # Business logic
│       │   └── ViewModels.kt         # UI state management
│       └── ui/
│           ├── theme/                # Dark theme + typography
│           ├── components/           # Reusable UI components
│           └── screens/              # Main, Policy, Logs screens
├── ksu-module/
│   ├── module.prop                   # KSU module metadata
│   ├── post-fs-data.sh              # Boot persistence service
│   ├── boot-completed.sh            # Cache flush on boot
│   ├── emergency_restore.sh         # Emergency recovery
│   └── AccountGuard-BootService-KSU-v1.zip
└── README.md
```

---

## How Visibility Works (Technical)

```
App calls AccountManager.getAccountsByType("com.google")
    ↓
AccountManagerService.getAccountsInternal()
    ↓
For each account, calls resolveAccountVisibility(account, callingPackage)
    ↓
Checks: visibility table in accounts_de.db for (account_id, package_name)
    ↓
If visibility = 4 (VISIBILITY_NOT_VISIBLE): account excluded from result
If visibility = 1 (VISIBILITY_VISIBLE): account included
    ↓
Filtered list returned to app
```

AccountGuard writes `visibility = 4` for the targets you choose.  
To restore, AccountGuard deletes that row → falls back to default (visible).

---

## Proof You Can Test Manually

```bash
# 1. Get account ID
adb shell su -c "sqlite3 /data/system_de/0/accounts_de.db 'SELECT _id, name FROM accounts WHERE type=\"com.google\";'"

# 2. Hide from YouTube (account_id = 3 in this example)
adb shell su -c "sqlite3 /data/system_de/0/accounts_de.db 'INSERT OR REPLACE INTO visibility VALUES (NULL, 3, \"com.google.android.youtube\", 4);'"

# 3. Flush cache
adb shell am broadcast -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION

# 4. Verify: open YouTube → account should not appear

# 5. Restore
adb shell su -c "sqlite3 /data/system_de/0/accounts_de.db 'DELETE FROM visibility WHERE accounts_id=3 AND package_name=\"com.google.android.youtube\";'"
adb shell am broadcast -a android.accounts.LOGIN_ACCOUNTS_CHANGED_ACTION
```
