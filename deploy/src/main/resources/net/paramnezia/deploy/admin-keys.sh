#!/bin/sh
# Adds and removes co-administrators' SSH keys for this login (the user the app logs in as).
#   PN_ADD: OpenSSH public key lines to authorize, one per line (added once, idempotent);
#   PN_DEL: base64 key blobs to remove (every line carrying one goes).
# Writes in place (same file, same owner and mode) and never touches other keys.
set -e
home=${PN_HOME:-$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6)}
[ -n "$home" ] || home=$HOME
dir="$home/.ssh"; f="$dir/authorized_keys"
umask 077
mkdir -p "$dir"; touch "$f"
tmp=$(mktemp "$dir/.paramnezia.XXXXXX")
trap 'rm -f "$tmp" "$tmp.n"' EXIT
cat "$f" > "$tmp"
printf '%s\n' "${PN_DEL:-}" | while IFS= read -r blob; do
  [ -n "$blob" ] || continue
  grep -vF -- "$blob" "$tmp" > "$tmp.n" || true
  cat "$tmp.n" > "$tmp"
done
printf '%s\n' "${PN_ADD:-}" | while IFS= read -r line; do
  [ -n "$line" ] || continue
  blob=$(printf '%s\n' "$line" | awk '{print $2}')
  [ -n "$blob" ] || continue
  grep -qF -- "$blob" "$tmp" || printf '%s\n' "$line" >> "$tmp"
done
cat "$tmp" > "$f"
chmod 700 "$dir"; chmod 600 "$f"
# SELinux (CentOS, Fedora): the context sshd expects.
if command -v restorecon >/dev/null 2>&1; then restorecon -R "$dir" 2>/dev/null || true; fi
echo "::result::keys=$(grep -c . "$f" || true)"
