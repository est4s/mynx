#!/usr/bin/env python3
"""Writes every release's notes from GitHub to OUT (JSON, newest first)
for the tools (/opt/mynx/release-notes.json: `mynx notes`, and What's
new after an update). Needs `gh` signed in (GH_TOKEN in CI).

  scripts/release-notes.py OUT [--tag TAG --notes-out FILE]

--tag: the release being built, not published yet. Its notes are
generated the way `gh release create --generate-notes` would, added to
OUT and written to FILE, for `gh release create --notes-file`.
"""
import argparse
import datetime
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools", "lib"))
from mynx.notes import version_key  # noqa: E402

REPO = os.environ.get("GITHUB_REPOSITORY", "{owner}/{repo}")


def gh(*args):
    return subprocess.run(["gh", "api", *args], check=True, capture_output=True, text=True).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--tag")
    parser.add_argument("--notes-out")
    args = parser.parse_args()
    if bool(args.tag) != bool(args.notes_out):
        parser.error("--tag and --notes-out go together")

    lines = gh("--paginate", f"repos/{REPO}/releases",
               "--jq", ".[] | select(.draft | not) | {tag_name, published_at, body}")
    found = {r["tag_name"]: r for r in map(json.loads, lines.splitlines())}
    if args.tag:
        if args.tag not in found:
            body = json.loads(gh("--method", "POST", f"repos/{REPO}/releases/generate-notes",
                                 "-f", f"tag_name={args.tag}"))["body"]
            now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            found[args.tag] = {"tag_name": args.tag, "published_at": now, "body": body}
        with open(args.notes_out, "w") as f:
            f.write(found[args.tag]["body"] or "")

    releases = [{"version": tag.removeprefix("v"), "published": r["published_at"], "notes": r["body"] or ""}
                for tag, r in found.items() if version_key(tag)]
    releases.sort(key=lambda r: version_key(r["version"]), reverse=True)
    with open(args.out, "w") as f:
        json.dump(releases, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"{len(releases)} releases' notes in {args.out}")


if __name__ == "__main__":
    main()
