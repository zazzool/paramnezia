#!/usr/bin/env python3
"""
Builds the bundled "Russia goes direct" lists for app/src/main/assets/bypass/.

Sources (public data):
  * ipverse/rir-ip   – RU IPv4/IPv6 blocks from RIR delegation files (CC0)
  * v2fly/domain-list-community – geosite:category-ru (MIT)

The IPv4/IPv6 lists are ordered by importance, so the app can take the first N lines that fit the
Android route budget of the device: blocks that host key Russian services come first (found by
resolving well-known domains), then the rest from the largest block to the smallest.

Run: python3 tools/bypass-lists/generate.py [--out DIR]
Stdlib only, so it runs as-is in CI.
"""
import argparse
import datetime
import ipaddress
import json
import pathlib
import re
import socket
import sys
import urllib.request

IPVERSE = "https://raw.githubusercontent.com/ipverse/rir-ip/master/country/ru/{}-aggregated.txt"
V2FLY = "https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/{}"

HERE = pathlib.Path(__file__).resolve().parent

# Services whose address blocks must never be dropped by the route budget.
KEY_DOMAINS = [
    "gosuslugi.ru", "esia.gosuslugi.ru", "nalog.gov.ru", "mos.ru", "pfr.gov.ru", "sfr.gov.ru", "kremlin.ru",
    "online.sberbank.ru", "sberbank.ru", "sber.ru", "tbank.ru", "id.tbank.ru", "vtb.ru", "online.vtb.ru",
    "alfabank.ru", "gazprombank.ru", "raiffeisen.ru", "psbank.ru", "sovcombank.ru", "pochtabank.ru", "nspk.ru",
    "yandex.ru", "ya.ru", "yandex.net", "dzen.ru", "kinopoisk.ru", "music.yandex.ru", "taxi.yandex.ru",
    "vk.com", "vk.ru", "userapi.com", "vkvideo.ru", "mail.ru", "ok.ru", "max.ru", "rutube.ru",
    "ozon.ru", "wildberries.ru", "wb.ru", "avito.ru", "market.yandex.ru", "megamarket.ru", "lamoda.ru",
    "2gis.ru", "rzd.ru", "aeroflot.ru", "pochta.ru", "cdek.ru", "hh.ru", "kaspersky.ru",
    "mts.ru", "megafon.ru", "beeline.ru", "t2.ru", "rt.ru", "rostelecom.ru",
    "ivi.ru", "okko.tv", "premier.one", "start.ru", "kion.ru", "litres.ru", "rbc.ru", "ria.ru", "tass.ru",
]


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "paramnezia-bypass-generator"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8")


def read_blocks(text: str):
    return [ipaddress.ip_network(l.strip(), strict=False) for l in text.splitlines() if l.strip() and not l.startswith("#")]


def resolve_all(domains):
    found = set()
    for d in domains:
        for family in (socket.AF_INET, socket.AF_INET6):
            try:
                for info in socket.getaddrinfo(d, 443, family, socket.SOCK_STREAM):
                    found.add(ipaddress.ip_address(info[4][0]))
            except OSError:
                pass
    return found


def order_blocks(blocks, key_ips):
    collapsed = list(ipaddress.collapse_addresses(blocks))
    key = [b for b in collapsed if any(ip.version == b.version and ip in b for ip in key_ips)]
    key_set = set(key)
    rest = sorted((b for b in collapsed if b not in key_set), key=lambda b: (b.prefixlen, int(b.network_address)))
    key.sort(key=lambda b: (b.prefixlen, int(b.network_address)))
    return key, rest


def v2fly_domains(name, seen=None):
    """Expands a v2fly list with its includes into (domain-suffixes, full-domains)."""
    seen = seen if seen is not None else set()
    if name in seen:
        return set(), set()
    seen.add(name)
    suffixes, full = set(), set()
    for raw in fetch(V2FLY.format(name)).splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        attrs = re.findall(r"@(\S+)", line)
        line = line.split(" ", 1)[0]
        # Ad and tracking hosts are better blocked by the tunnel's DNS than sent direct.
        if "ads" in attrs:
            continue
        if line.startswith("include:"):
            s, f = v2fly_domains(line[len("include:"):].split("@")[0], seen)
            suffixes |= s
            full |= f
        elif line.startswith("full:"):
            full.add(line[5:].lower())
        elif line.startswith(("regexp:", "keyword:")):
            continue
        else:
            suffixes.add(line.removeprefix("domain:").lower())
    return suffixes, full


def minimize_suffixes(suffixes):
    out = set()
    for d in sorted(suffixes, key=lambda x: x.count(".")):
        parts = d.split(".")
        if not any(".".join(parts[i:]) in out for i in range(1, len(parts))):
            out.add(d)
    return sorted(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE.parent.parent / "app/src/main/assets/bypass"))
    ap.add_argument("--no-resolve", action="store_true", help="skip DNS lookups for key services")
    args = ap.parse_args()
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    v4 = read_blocks(fetch(IPVERSE.format("ipv4")))
    v6 = read_blocks(fetch(IPVERSE.format("ipv6")))
    key_ips = set() if args.no_resolve else resolve_all(KEY_DOMAINS)

    stats = {}
    for fam, blocks in (("ipv4", v4), ("ipv6", v6)):
        key, rest = order_blocks(blocks, key_ips)
        lines = [str(b) for b in key + rest]
        (out / f"{fam}.txt").write_text("\n".join(lines) + "\n")
        stats[fam] = {"blocks": len(lines), "priority": len(key)}

    suffixes, full = v2fly_domains("category-ru")
    domains = minimize_suffixes(suffixes)
    full = sorted(f for f in full if not any(f == s or f.endswith("." + s) for s in domains))
    (out / "domains.txt").write_text("\n".join(domains + ["full:" + f for f in full]) + "\n")
    stats["domains"] = len(domains) + len(full)

    meta = {
        "version": datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d"),
        "sources": {"ip": "ipverse/rir-ip (CC0)", "domains": "v2fly/domain-list-community category-ru (MIT)"},
        "stats": stats,
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(meta, ensure_ascii=False), file=sys.stderr)


if __name__ == "__main__":
    main()
