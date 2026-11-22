import base64
import binascii
import hmac
import ipaddress
import json
import re
import time
import os
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from socketserver import UnixStreamServer
from tempfile import NamedTemporaryFile

root = Path("/var/lib/paramnezia-mirror/files")
target = root / "fleet.json"
socket_path = root / "writer.sock"
token = (Path(os.environ["CREDENTIALS_DIRECTORY"]) / "token").read_text().strip()
family = os.environ["FAMILY_ID"]

zone = Path("/var/lib/paramnezia-dns/family.zone")

def update_dns(endpoint):
    existing = zone.read_text()
    if f"@ IN A {endpoint}\n" in existing:
        return
    previous = int(re.search(r" ; serial=(\d+)", existing)[1])
    serial = max(previous + 1, int(time.time()))
    records = (f"$ORIGIN awg-family.psync.ru.\n$TTL 60\n"
               f"@ IN SOA psync.ru. hostmaster.psync.ru. ({serial} 300 60 86400 60) ; serial={serial}\n"
               f"@ IN NS psync.ru.\n@ IN A {endpoint}\n")
    with NamedTemporaryFile(mode="w", dir=zone.parent, delete=False) as f:
        temp = Path(f.name)
        try:
            f.write(records)
            f.flush()
            os.fchmod(f.fileno(), 0o644)
            os.fsync(f.fileno())
            temp.replace(zone)
        finally:
            temp.unlink(missing_ok=True)

class WriteManifest(BaseHTTPRequestHandler):
    def do_PUT(self):
        if self.path != f"/paramnezia/{family}/fleet.json":
            return self.send_error(404)
        auth = self.headers.get("Authorization", "").encode()
        if not hmac.compare_digest(auth, ("Bearer " + token).encode()):
            return self.send_error(403)
        try:
            endpoint = self.headers.get("X-Paramnezia-Endpoint")
            if endpoint is not None:
                address = ipaddress.IPv4Address(endpoint)
                if not address.is_global or address.is_multicast:
                    return self.send_error(400)
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 2 * 1024 * 1024:
                return self.send_error(413)
            self.connection.settimeout(10)
            wire = self.rfile.read(size)
            data = json.loads(wire)
            if (not isinstance(data, dict) or data.keys() - {"fleetId","version","body","sig","signer"}
                    or data["fleetId"] != family or type(data["version"]) is not int
                    or not 0 < data["version"] < 2**63
                    or len(base64.b64decode(data["sig"], validate=True)) != 64
                    or len(base64.b64decode(data["body"], validate=True)) < 28):
                return self.send_error(400)
            if data.get("signer") is not None and len(base64.b64decode(data["signer"], validate=True)) != 32:
                return self.send_error(400)
        except (ValueError, TypeError, KeyError, binascii.Error):
            return self.send_error(400)
        current = json.loads(target.read_bytes())
        if data["version"] < current["version"] or (data["version"] == current["version"] and current != data):
            return self.send_error(409)
        if data != current:
            with NamedTemporaryFile(dir=root, delete=False) as f:
                tmp = Path(f.name)
                try:
                    f.write(wire)
                    f.flush()
                    os.fchmod(f.fileno(), 0o644)
                    os.fsync(f.fileno())
                    tmp.replace(target)
                finally:
                    tmp.unlink(missing_ok=True)
        if endpoint is not None:
            update_dns(endpoint)
        self.send_response(204)
        self.end_headers()

    def address_string(self):
        return "nginx"

socket_path.unlink(missing_ok=True)
with UnixStreamServer(str(socket_path), WriteManifest) as server:
    os.chmod(socket_path, 0o666)
    server.serve_forever()
