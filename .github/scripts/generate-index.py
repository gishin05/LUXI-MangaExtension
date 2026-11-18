#!/usr/bin/env python3
"""Generate v2 (index.json) and legacy (repo.json + index.min.json) extension store indices."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path
from typing import Any


def extract_apk_info(apk_path: Path, aapt2: str) -> dict[str, Any]:
    out = subprocess.check_output(
        [aapt2, "dump", "badging", str(apk_path)],
        text=True,
        stderr=subprocess.DEVNULL,
    )
    pkg_match = re.search(
        r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
        out,
    )
    if not pkg_match:
        raise RuntimeError(f"Could not parse package info from {apk_path}")

    pkg_name, version_code_str, version_name = pkg_match.groups()
    version_code = int(version_code_str)

    label_match = re.search(r"application-label:'([^']+)'", out)
    raw_label = label_match.group(1) if label_match else pkg_name
    ext_name = raw_label.removeprefix("Luxi: ").removeprefix("Tachiyomi: ")

    # Read AndroidManifest XML dump for meta-data values
    xml_out = subprocess.check_output(
        [aapt2, "dump", "xmltree", str(apk_path), "--file", "AndroidManifest.xml"],
        text=True,
        stderr=subprocess.DEVNULL,
    )

    def get_meta(key: str) -> str | None:
        pattern = (
            rf'A: android:name\(0x[0-9a-f]+\)="{re.escape(key)}"[^\n]*\n'
            r'\s*A: android:value\(0x[0-9a-f]+\)=([^\n]+)'
        )
        m = re.search(pattern, xml_out)
        if not m:
            return None
        val = m.group(1).strip()
        if val.startswith('"') and '"' in val[1:]:
            return val[1 : val.index('"', 1)]
        if val.startswith("(type 0x10)"):
            return str(int(val.split(")")[1], 16))
        if val.startswith("(type 0x4)"):
            # Float packed as hex IEEE-754
            import struct
            raw_int = int(val.split(")")[1], 16)
            return f"{struct.unpack('!f', struct.pack('!I', raw_int))[0]:.1f}"
        return val

    ext_name = get_meta("tachiyomix.name") or ext_name
    ext_lib = get_meta("tachiyomix.extensionLib") or "1.4"
    cw_raw = get_meta("tachiyomix.contentWarning")
    nsfw_raw = get_meta("tachiyomi.extension.nsfw")

    if cw_raw == "2" or nsfw_raw == "1":
        content_warning = "CONTENT_WARNING_NSFW"
        nsfw_int = 1
    elif cw_raw == "1":
        content_warning = "CONTENT_WARNING_MIXED"
        nsfw_int = 0
    else:
        content_warning = "CONTENT_WARNING_SAFE"
        nsfw_int = 0

    parts = pkg_name.split(".")
    lang = parts[4] if len(parts) >= 6 else "all"

    return {
        "name": ext_name,
        "pkg": pkg_name,
        "apk": apk_path.name,
        "lang": lang,
        "versionCode": version_code,
        "versionName": version_name,
        "extensionLib": ext_lib,
        "contentWarning": content_warning,
        "nsfw": nsfw_int,
    }


def extract_icon(apk_path: Path, icon_out: Path) -> bool:
    with zipfile.ZipFile(apk_path, "r") as zf:
        candidates = [
            n
            for n in zf.namelist()
            if n.startswith("res/") and "ic_launcher" in n and n.endswith(".png")
        ]
        if not candidates:
            candidates = [
                n for n in zf.namelist() if n.startswith("res/") and n.endswith(".png")
            ]
        if not candidates:
            return False
        # Pick largest PNG as highest density icon
        best = max(candidates, key=lambda n: zf.getinfo(n).file_size)
        icon_out.parent.mkdir(parents=True, exist_ok=True)
        icon_out.write_bytes(zf.read(best))
        return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-dir", type=Path, required=True, help="Path to repo branch output dir")
    parser.add_argument("--base-url", required=True, help="Raw base URL (no trailing slash)")
    parser.add_argument("--signing-key", required=True, help="Lowercase SHA-256 cert fingerprint")
    parser.add_argument("--website", default="https://github.com/gishin05/LUXI-MangaExtension")
    parser.add_argument("--aapt2", default="aapt2")
    parser.add_argument("--sources-json", type=Path, default=None, help="Optional JSON map of pkg -> sources list")
    args = parser.parse_args()

    apk_dir = args.repo_dir / "apk"
    icon_dir = args.repo_dir / "icon"
    apk_dir.mkdir(parents=True, exist_ok=True)
    icon_dir.mkdir(parents=True, exist_ok=True)

    sources_map: dict[str, list[dict[str, Any]]] = {}
    if args.sources_json and args.sources_json.exists():
        sources_map = json.loads(args.sources_json.read_text(encoding="utf-8"))

    v2_extensions = []
    legacy_extensions = []

    for apk_path in sorted(apk_dir.glob("*.apk")):
        info = extract_apk_info(apk_path, args.aapt2)
        pkg = info["pkg"]
        extract_icon(apk_path, icon_dir / f"{pkg}.png")

        pkg_sources = sources_map.get(
            pkg,
            [
                {
                    "id": 0,
                    "name": info["name"],
                    "language": info["lang"],
                    "homeUrl": "",
                }
            ],
        )

        v2_extensions.append(
            {
                "name": info["name"],
                "packageName": pkg,
                "resources": {
                    "apkUrl": f"{args.base_url}/apk/{info['apk']}",
                    "iconUrl": f"{args.base_url}/icon/{pkg}.png",
                },
                "extensionLib": info["extensionLib"],
                "versionCode": info["versionCode"],
                "versionName": info["versionName"],
                "contentWarning": info["contentWarning"],
                "sources": pkg_sources,
            }
        )

        legacy_extensions.append(
            {
                "name": f"Tachiyomi: {info['name']}",
                "pkg": pkg,
                "apk": info["apk"],
                "lang": info["lang"],
                "code": info["versionCode"],
                "version": info["versionName"],
                "nsfw": info["nsfw"],
                "sources": [
                    {
                        "id": s["id"],
                        "lang": s.get("language", info["lang"]),
                        "name": s["name"],
                        "baseUrl": s.get("homeUrl", ""),
                    }
                    for s in pkg_sources
                ],
            }
        )

    v2_index = {
        "name": "Luxi (Manga)",
        "badgeLabel": "Luxi",
        "signingKey": args.signing_key.lower(),
        "contact": {
            "website": args.website,
            "discord": None,
        },
        "extensionList": {
            "extensions": v2_extensions,
        },
        "extensionListUrl": None,
    }

    legacy_repo = {
        "meta": {
            "name": "Luxi (Manga)",
            "shortName": "Luxi",
            "website": args.website,
            "signingKeyFingerprint": args.signing_key.lower(),
        }
    }

    (args.repo_dir / "index.json").write_text(
        json.dumps(v2_index, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (args.repo_dir / "repo.json").write_text(
        json.dumps(legacy_repo, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (args.repo_dir / "index.min.json").write_text(
        json.dumps(legacy_extensions, separators=(",", ":"), ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"Generated index with {len(v2_extensions)} extensions.")


if __name__ == "__main__":
    main()
