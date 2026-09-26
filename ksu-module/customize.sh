#!/system/bin/sh
# VollaTorchStrength KernelSU installer
SKIPUNZIP=0
ui_print "  *****************************************"
ui_print "  * Volla Torch Strength — root daemon    *"
ui_print "  *****************************************"

DEV=$(getprop ro.product.device)
ui_print "- Device: $DEV"
if [ "$DEV" != "algiz" ]; then
    ui_print "! Targets 'algiz' (Volla Quintus). Yours: '$DEV'. Aborting."
    abort "! Wrong device."
fi

# sanity-check the flash sysfs nodes exist
for n in /sys/class/leds/mt6360_flash_ch1/brightness /sys/class/leds/mt6360_flash_ch2/brightness; do
    [ -w "$n" ] || [ -e "$n" ] || abort "! $n missing — unexpected ROM."
done
ui_print "- Flash channels present (max_brightness 31)."

# the prebuilt daemon binary ships in the module root
[ -f "$MODPATH/vollatorchd" ] || abort "! vollatorchd binary missing from zip."
set_perm "$MODPATH/vollatorchd" 0 0 0755
set_perm "$MODPATH/service.sh"  0 0 0755

ui_print " "
ui_print "- Daemon installed. Reboot to start it."
ui_print "- Then install the VollaTorchStrength APK and enable it in LSPosed"
ui_print "  with scope: System Framework, SystemUI, FlashDim."
