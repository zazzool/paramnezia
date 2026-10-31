#!/usr/bin/env python3
"""
Builds the bundled "Russia goes direct" lists for app/src/main/assets/bypass/.

Sources (public data):
  * ipverse/rir-ip        – RU IPv4/IPv6 blocks from RIR delegation files (CC0)
  * ipverse/as-ip-blocks  – prefixes of the key Russian services' own networks (CC0)
  * v2fly/domain-list-community – geosite:category-ru (direct) and category-ads-all (block) (MIT)
  * extra-direct.txt, trackers-ru.txt, allow.txt – curated here

The IPv4/IPv6 lists are ordered by importance, so the app can take the first N lines that fit the
Android route budget of the device: blocks that host key Russian services come first (found by
the networks of the key services' ASNs and resolving well-known domains), then the rest from the largest block to the smallest.

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
ASN = "https://raw.githubusercontent.com/ipverse/as-ip-blocks/master/as/{}/aggregated.json"

# Networks of the services that must always go direct, whatever the device's route budget.
KEY_ASNS = {
    13238: "Yandex", 200350: "Yandex.Cloud", 210656: "Yandex.Cloud", 215013: "Yandex CDN",
    47541: "VK", 47542: "VK CDN", 47764: "VK / Mail.ru", 28709: "VK regional CDN", 60863: "VK", 49797: "VK",
    35237: "Sberbank", 206673: "Sberbank-Telecom", 205638: "T-Bank", 15632: "Alfa-Bank",
    44386: "Ozon", 43247: "YooMoney",
}

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


def order_blocks(blocks, key_ips, key_nets):
    collapsed = list(ipaddress.collapse_addresses(blocks + key_nets))
    key = [b for b in collapsed
           if any(ip.version == b.version and ip in b for ip in key_ips)
           or any(n.version == b.version and n.subnet_of(b) for n in key_nets)]
    key_set = set(key)
    rest = sorted((b for b in collapsed if b not in key_set), key=lambda b: (b.prefixlen, int(b.network_address)))
    key.sort(key=lambda b: (b.prefixlen, int(b.network_address)))
    return key, rest


_v2fly_cache = {}


def v2fly_entries(name, stack=()):
    """All entries of a v2fly list with its includes expanded: a set of (kind, domain, attrs)."""
    if name in _v2fly_cache:
        return _v2fly_cache[name]
    if name in stack:
        return set()
    out = set()
    for raw in fetch(V2FLY.format(name)).splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        attrs = frozenset(re.findall(r"@(\S+)", line))
        line = line.split(" ", 1)[0]
        if line.startswith("include:"):
            # `include:name@attr` takes only the entries carrying attr; `@-attr` those without it.
            # The filter is written either way: `include:yandex@ads` or `include:yandex @ads`.
            target, *filters = line[len("include:"):].split("@")
            filters += sorted(attrs)
            for e in v2fly_entries(target, stack + (name,)):
                if all((f[1:] not in e[2]) if f.startswith("-") else (f in e[2]) for f in filters):
                    out.add(e)
        elif line.startswith("full:"):
            out.add(("full", line[5:].lower(), attrs))
        elif line.startswith(("regexp:", "keyword:")):
            continue
        else:
            out.add(("suffix", line.removeprefix("domain:").lower(), attrs))
    _v2fly_cache[name] = out
    return out


def v2fly_domains(name, skip_ads=False):
    """(domain-suffixes, full-domains) of a v2fly list."""
    suffixes, full = set(), set()
    for kind, d, attrs in v2fly_entries(name):
        # Ad and tracking hosts are blocked by the DNS filter, never sent direct.
        if skip_ads and "ads" in attrs:
            continue
        (full if kind == "full" else suffixes).add(d)
    return suffixes, full


def read_rules(path):
    suffixes, full = set(), set()
    for raw in path.read_text().splitlines():
        line = raw.split("#", 1)[0].strip().lower()
        if line.startswith("full:"):
            full.add(line[5:])
        elif line:
            suffixes.add(line)
    return suffixes, full


def asn_prefixes():
    blocks = []
    for asn in KEY_ASNS:
        try:
            p = json.loads(fetch(ASN.format(asn)))["prefixes"]
            blocks += [ipaddress.ip_network(x) for x in p.get("ipv4", []) + p.get("ipv6", [])]
        except Exception as e:  # one missing network should not stop the build
            print(f"AS{asn}: {e}", file=sys.stderr)
    return blocks


def write_rules(path, suffixes, full):
    domains = minimize_suffixes(suffixes)
    full = sorted(f for f in full if not any(f == s or f.endswith("." + s) for s in domains))
    path.write_text("\n".join(domains + ["full:" + f for f in full]) + "\n")
    return len(domains) + len(full)


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
    key_nets = asn_prefixes()

    stats = {}
    for fam, blocks in (("ipv4", v4), ("ipv6", v6)):
        nets = [n for n in key_nets if n.version == blocks[0].version]
        key, rest = order_blocks(blocks, key_ips, nets)
        lines = [str(b) for b in key + rest]
        (out / f"{fam}.txt").write_text("\n".join(lines) + "\n")
        stats[fam] = {"blocks": len(lines), "priority": len(key)}

    suffixes, full = v2fly_domains("category-ru", skip_ads=True)
    es, ef = read_rules(HERE / "extra-direct.txt")
    stats["domains"] = write_rules(out / "domains.txt", suffixes | es, full | ef)

    bs, bf = v2fly_domains("category-ads-all")
    ts, tf = read_rules(HERE / "trackers-ru.txt")
    stats["blocked"] = write_rules(out / "blocklist.txt", bs | ts, bf | tf)
    (out / "allow.txt").write_text((HERE / "allow.txt").read_text())

    meta = {
        "version": datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d"),
        "sources": {
            "ip": "ipverse/rir-ip, ipverse/as-ip-blocks (CC0)",
            "domains": "v2fly/domain-list-community category-ru (MIT) + extra-direct.txt",
            "blocklist": "v2fly/domain-list-community category-ads-all (MIT) + trackers-ru.txt",
        },
        "stats": stats,
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(meta, ensure_ascii=False), file=sys.stderr)


if __name__ == "__main__":
    main()
