#!/usr/bin/env python3
"""Generate v2 (index.json) and legacy (repo.json + index.min.json) extension store indices."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
import zipfile
from pathlib import Path
from typing import Any


def generate_source_id(name: str, lang: str, version_id: int = 1) -> int:
    key = f"{name.lower()}/{lang}/{version_id}".encode("utf-8")
    digest = hashlib.md5(key).digest()
    return int.from_bytes(digest[:8], "big") & 0x7FFFFFFFFFFFFFFF


def scan_source_metadata(src_dir: Path | None) -> dict[str, list[dict[str, Any]]]:
    """Scan src/<lang>/<ext>/ for baseUrl, name, lang, and versionId to populate sources."""
    if not src_dir or not src_dir.exists():
        return {}

    result: dict[str, list[dict[str, Any]]] = {}
    for lang_dir in sorted(src_dir.iterdir()):
        if not lang_dir.is_dir():
            continue
        lang = lang_dir.name
        for ext_dir in sorted(lang_dir.iterdir()):
            if not ext_dir.is_dir():
                continue
            gradle_file = ext_dir / "build.gradle"
            if not gradle_file.exists():
                gradle_file = ext_dir / "build.gradle.kts"
            if not gradle_file.exists():
                continue

            gradle_text = gradle_file.read_text(encoding="utf-8", errors="ignore")
            pkg_match = re.search(r'extPkg\s*=\s*"([^"]+)"', gradle_text)
            pkg = pkg_match.group(1) if pkg_match else f"eu.kanade.tachiyomi.extension.{lang}.{ext_dir.name}"
            name_match = re.search(r'extName\s*=\s*"([^"]+)"', gradle_text)
            ext_name = name_match.group(1) if name_match else ext_dir.name

            kt_files = list(ext_dir.rglob("*.kt"))
            combined_kt = "\n".join(f.read_text(encoding="utf-8", errors="ignore") for f in kt_files)

            base_url_match = re.search(r'baseUrl\s*=\s*"([^"]+)"', combined_kt)
            home_url = base_url_match.group(1) if base_url_match else ""

            src_name_match = re.search(r'(?:override\s+val\s+)?name\s*=\s*"([^"]+)"', combined_kt)
            src_name = src_name_match.group(1) if src_name_match else ext_name

            src_lang_match = re.search(r'(?:override\s+val\s+)?lang\s*=\s*"([^"]+)"', combined_kt)
            src_lang = src_lang_match.group(1) if src_lang_match else lang

            ver_id_match = re.search(r'override\s+val\s+versionId\s*=\s*(\d+)', combined_kt)
            version_id = int(ver_id_match.group(1)) if ver_id_match else 1

            result[pkg] = [
                {
                    "id": generate_source_id(src_name, src_lang, version_id),
                    "name": src_name,
                    "language": src_lang,
                    "homeUrl": home_url,
                }
            ]
    return result


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

    xml_out = subprocess.check_output(
        [aapt2, "dump", "xmltree", str(apk_path), "--file", "AndroidManifest.xml"],
        text=True,
        stderr=subprocess.DEVNULL,
    )

    def get_meta(key: str) -> str | None:
        pattern = (
            rf'A: (?:http://schemas\.android\.com/apk/res/android:|android:)name\(0x[0-9a-f]+\)="{re.escape(key)}"[^\n]*\n'
            r'\s*A: (?:http://schemas\.android\.com/apk/res/android:|android:)value\(0x[0-9a-f]+\)=([^\n]+)'
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
            raw_int = int(val.split(")")[1], 16)
            return f"{struct.unpack('!f', struct.pack('!I', raw_int))[0]:.1f}"
        return val

    ext_name = get_meta("tachiyomix.name") or ext_name
    ext_lib = get_meta("tachiyomix.extensionLib") or "1.4"
    cw_raw = get_meta("tachiyomix.contentWarning")
    nsfw_raw = get_meta("tachiyomi.extension.nsfw")

    if cw_raw == "2" or (cw_raw is None and nsfw_raw == "1"):
        content_warning = "CONTENT_WARNING_NSFW"
        nsfw_int = 1
    elif cw_raw == "1":
        content_warning = "CONTENT_WARNING_MIXED"
        nsfw_int = 1
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
    parser.add_argument("--src-dir", type=Path, default=Path("src"), help="Path to src/<lang>/<ext> modules")
    parser.add_argument("--sources-json", type=Path, default=None, help="Optional JSON map of pkg -> sources list")
    args = parser.parse_args()

    apk_dir = args.repo_dir / "apk"
    icon_dir = args.repo_dir / "icon"
    apk_dir.mkdir(parents=True, exist_ok=True)
    icon_dir.mkdir(parents=True, exist_ok=True)

    sources_map = scan_source_metadata(args.src_dir)
    if args.sources_json and args.sources_json.exists():
        sources_map.update(json.loads(args.sources_json.read_text(encoding="utf-8")))

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
                    "id": generate_source_id(info["name"], info["lang"], 1),
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
                    "iconUrl": f"{args.base_url}/icon/{pkg}.png?v={info['versionCode']}",
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
