#!/system/bin/sh
# VollaTorchStrength daemon — started late each boot by KernelSU.
# Launches vollatorchd (root) which listens on abstract socket "\0vollatorchd"
# and writes torch levels to the mt6360 flash channels on request.
MODDIR=${0%/*}

# wait for boot to complete so sysfs LED nodes exist
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 3

BIN="$MODDIR/vollatorchd"
if [ -x "$BIN" ]; then
    # respawn loop: if the daemon ever dies, bring it back (still event-driven,
    # this loop only runs on actual exit, not per-request)
    ( while true; do
        "$BIN"
        sleep 2
      done ) &
fi
