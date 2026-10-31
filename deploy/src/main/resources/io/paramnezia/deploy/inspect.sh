#!/usr/bin/env bash
# Reports which AmneziaVPN-style containers exist on the host: "::container::<name> <state>".
set -eu
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
echo "::result::uname=$(uname -sr)"
if ! command -v docker >/dev/null 2>&1; then echo "::result::docker=missing"; exit 0; fi
echo "::result::docker=present"
$SUDO docker ps -a --format '{{.Names}} {{.State}}' 2>/dev/null | grep '^amnezia-' | sed 's/^/::container::/' || true
