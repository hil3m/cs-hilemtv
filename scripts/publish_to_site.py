#!/usr/bin/env python3
"""CloudStream .cs3 dosyasını ve depo manifestlerini yayımlar."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

PLUGIN_NAME = "HilemTVProvider"
DISPLAY_NAME = "хилемTV"
DESCRIPTION = "хилемTV онлайн-просмотр фильмов и сериалов"
AUTHOR = "hilem"
SITE_ROOT = "https://tv.hilem.ru"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
MODULE_BUILD_FILE = PROJECT_ROOT / "HilemTVProvider" / "build.gradle.kts"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_plugin_version() -> int:
    content = MODULE_BUILD_FILE.read_text(encoding="utf-8")
    match = re.search(r"(?m)^\s*version\s*=\s*(\d+)\s*$", content)
    if not match:
        raise SystemExit(f"Eklenti sürümü okunamadı: {MODULE_BUILD_FILE}")
    return int(match.group(1))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cs3", type=Path)
    parser.add_argument("repository_dir", type=Path)
    args = parser.parse_args()

    source = args.cs3.resolve()
    target_dir = args.repository_dir.resolve()
    if not source.is_file() or source.suffix.lower() != ".cs3":
        raise SystemExit(f"Geçerli .cs3 bulunamadı: {source}")

    version = read_plugin_version()
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / f"{PLUGIN_NAME}.cs3"
    shutil.copy2(source, target)

    plugin = {
        "iconUrl": f"{SITE_ROOT}/icons/icon-192x192.png",
        "fileHash": f"sha256-{sha256(target)}",
        "apiVersion": 1,
        "repositoryUrl": f"{SITE_ROOT}/cloudstream/repo.json",
        "fileSize": target.stat().st_size,
        "status": 3,
        "authors": [AUTHOR],
        "tvTypes": ["Movie", "TvSeries"],
        "language": "ru",
        "version": version,
        "internalName": PLUGIN_NAME,
        "description": DESCRIPTION,
        "url": f"{SITE_ROOT}/cloudstream/{PLUGIN_NAME}.cs3",
        "name": DISPLAY_NAME,
    }
    repository = {
        "name": DISPLAY_NAME,
        "description": DESCRIPTION,
        "manifestVersion": 1,
        "pluginLists": [f"{SITE_ROOT}/cloudstream/plugins.json"],
    }

    (target_dir / "plugins.json").write_text(
        json.dumps([plugin], ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (target_dir / "repo.json").write_text(
        json.dumps(repository, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(f"Yayımlandı: {target}")
    print(f"Eklenti sürümü: {version}")
    print(f"Depo: {SITE_ROOT}/cloudstream/repo.json")


if __name__ == "__main__":
    main()
