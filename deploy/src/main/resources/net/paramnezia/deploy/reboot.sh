#!/bin/sh
# Reboots the whole server, now. Forced: services are not stopped one by one, so a hung container
# or service cannot hold the reboot up; the disks are synced first and filesystems are unmounted
# cleanly by systemd. Returns at once and the reboot follows two seconds later, outside this SSH
# session (a transient systemd timer), so the answer gets back before the connection drops.
if [ "$(id -u)" -eq 0 ]; then S=''; else S='sudo -n'; fi
if [ -n "$S" ] && ! $S true 2>/dev/null; then echo "::error::sudo_password_required"; exit 1; fi
sync
if command -v systemd-run >/dev/null 2>&1 && $S systemd-run --quiet --on-active=2 --timer-property=AccuracySec=100ms /bin/sh -c 'sync; systemctl reboot --force || reboot -f' 2>/dev/null; then
  echo "::result::via=systemd"
else
  # No systemd (or it refused): a detached shell that ignores the hangup when SSH goes away.
  nohup $S sh -c 'sleep 2; sync; reboot -f || { echo 1 > /proc/sys/kernel/sysrq; echo b > /proc/sysrq-trigger; }' >/dev/null 2>&1 </dev/null &
  echo "::result::via=nohup"
fi
echo "::result::rebooting=1"
