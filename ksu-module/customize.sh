#!/sbin/sh
# AccountGuard KSU Module Installer
# Compatible with KernelSU-Next + BreZygisk

SKIPUNZIP=1

# Extract module files
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'post-fs-data.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'boot-completed.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'emergency_restore.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'system/*' -d "$MODPATH" >&2

# Set permissions
set_perm "$MODPATH/post-fs-data.sh" root root 0755
set_perm "$MODPATH/boot-completed.sh" root root 0755
set_perm "$MODPATH/emergency_restore.sh" root root 0755
set_perm_recursive "$MODPATH/system" root root 0755 0755

# Create config directory
mkdir -p "/data/adb/ksu/module_configs/accountguard-boot"

ui_print "- AccountGuard Boot Service installed"
ui_print "- Boot persistence for account visibility policies"
ui_print "- Emergency restore: adb shell su -c 'sh $MODPATH/emergency_restore.sh'"
