"""Validate the chronological public release sequence before building an APK."""

import argparse
import json
import re
from pathlib import Path


def tag_for_index(index):
    return f"v{1 + index // 10}.{index % 10}.0"


def validate(tag, version_code, releases):
    if not re.fullmatch(r"v[1-9][0-9]*\.[0-9]\.0", tag):
        raise ValueError("Use v1.0.0, v1.1.0, …, v1.9.0, v2.0.0; no prerelease suffixes.")
    if version_code < 1:
        raise ValueError("Android versionCode must be positive.")
    published = sorted((r for r in releases if not r["draft"]), key=lambda r: r["published_at"])
    for index, release in enumerate(published):
        if release["prerelease"] or release["tag_name"] != tag_for_index(index):
            raise ValueError("Published history must contain consecutive regular releases.")
    if published and published[-1]["tag_name"] == tag:
        # A workflow retry may replace assets of the same release, never an older one.
        existing = re.search(r"<!--\s*dogmatix-release:\s*(\{[^\n]*\})\s*-->", published[-1].get("body") or "")
        if not existing or json.loads(existing.group(1)).get("versionCode") != version_code:
            raise ValueError("Republishing must retain the existing release's Android build number.")
        predecessor = published[-2] if len(published) > 1 else None
    else:
        if tag != tag_for_index(len(published)):
            raise ValueError(f"Next release must be {tag_for_index(len(published))}.")
        predecessor = published[-1] if published else None
    if predecessor:
        marker = re.search(r"<!--\s*dogmatix-release:\s*(\{[^\n]*\})\s*-->", predecessor.get("body") or "")
        if not marker:
            raise ValueError("Previous release is missing its Android build metadata.")
        previous_code = json.loads(marker.group(1))["versionCode"]
        if isinstance(previous_code, bool) or not isinstance(previous_code, int) or version_code <= previous_code:
            raise ValueError("Android versionCode must increase even when public versions are renumbered.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("version_code", type=int)
    parser.add_argument("releases", type=Path)
    args = parser.parse_args()
    try:
        validate(args.tag, args.version_code, json.loads(args.releases.read_text()))
    except (ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Release policy: {error}\n")
    print(f"Release policy accepted: {args.tag}, Android build {args.version_code}.")
