#!/usr/bin/env python3
"""Fetch and verify the official OpenAI Codex app-server release asset.

This deliberately consumes the published binary; it does not build Rust for Android.
"""
from __future__ import annotations
import argparse, hashlib, json, os, shutil, stat, struct, tarfile, tempfile, urllib.request

OWNER_REPO = "openai/codex"
TAG = "rust-v0.162.1"
OUT = "app/src/main/assets/codex/codex-app-server"
TARGETS = {"aarch64-unknown-linux-musl": "aarch64", "x86_64-unknown-linux-musl": "x86_64"}
PINNED_DIGESTS = {
    "aarch64-unknown-linux-musl": (
        "codex-app-server-aarch64-unknown-linux-musl.tar.gz",
        "51fd0b6cac6223f97bcce9b98e78c7c0359a2cca20030548205babce98589bb4"
    ),
    "x86_64-unknown-linux-musl": (
        "codex-app-server-x86_64-unknown-linux-musl.tar.gz",
        "ff6892db3e3dcaab08097d34872a99e85d8beb320797c2841c81d50e83b73267"
    )
}

def request(url):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "Operit-Codex-CI"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as r: return r.read(), dict(r.headers)

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1024*1024), b""): h.update(b)
    return h.hexdigest()

def elf_arch(path):
    with open(path, "rb") as f:
        h = f.read(20)
    if len(h) < 20 or h[:4] != b"\x7fELF" or h[4] != 2 or h[5] != 1: return None
    return struct.unpack_from("<H", h, 18)[0]

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--repository", default="."); ap.add_argument("--tag", default=TAG); ap.add_argument("--target", choices=TARGETS, default="aarch64-unknown-linux-musl"); ap.add_argument("--output"); args = ap.parse_args()
    
    asset_name, expected = PINNED_DIGESTS.get(args.target, (None, None))
    download_url = f"https://github.com/{OWNER_REPO}/releases/download/{args.tag}/{asset_name}"
    
    api = f"https://api.github.com/repos/{OWNER_REPO}/releases/tags/{args.tag}"
    try:
        raw, _ = request(api)
        release = json.loads(raw)
        prefix = f"codex-app-server-{args.target}"
        asset = next((a for a in release.get("assets", []) if a.get("name", "").startswith(prefix) and a.get("name", "").endswith(".tar.gz")), None)
        if asset:
            asset_name = asset["name"]
            download_url = asset["browser_download_url"]
            digest = (asset.get("digest") or "").removeprefix("sha256:")
            if len(digest) == 64:
                expected = digest
    except Exception as e:
        print(f"Notice: GitHub API lookup ({e}); using direct release asset {asset_name}")

    if not asset_name or not expected:
        raise SystemExit(f"No release asset available for target {args.target}")

    expected_arch = TARGETS[args.target]
    if expected_arch == "x86_64" and not args.output:
        raise SystemExit("x86_64 target requires --output temporary path")
    root = os.path.abspath(args.repository)
    out = os.path.abspath(args.output) if args.output else os.path.join(root, OUT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    
    with tempfile.TemporaryDirectory() as td:
        archive = os.path.join(td, asset_name)
        data, _ = request(download_url)
        with open(archive, "wb") as f:
            f.write(data)
        got = sha256(archive)
        if got != expected:
            raise SystemExit(f"digest mismatch: {got} != {expected}")
        with tarfile.open(archive, "r:gz") as tar:
            members = [m for m in tar.getmembers() if m.isfile()]
            candidate = next((m for m in members if os.path.basename(m.name) in ("codex-app-server", f"codex-app-server-{args.target}")), None)
            if not candidate: raise SystemExit("archive has no codex-app-server")
            if candidate.size <= 0: raise SystemExit("empty app-server")
            src = tar.extractfile(candidate)
            assert src
            tmp = os.path.join(td, "server")
            with open(tmp, "wb") as f:
                f.write(src.read())
            machine = elf_arch(tmp)
            required_machine = {"aarch64": 183, "x86_64": 62}[expected_arch]
            if machine != required_machine:
                raise SystemExit(f"app-server ELF machine mismatch: {machine} != {required_machine}")
            shutil.copyfile(tmp, out)
            os.chmod(out, stat.S_IRUSR|stat.S_IWUSR|stat.S_IXUSR|stat.S_IRGRP|stat.S_IXGRP|stat.S_IROTH|stat.S_IXOTH)
            
    if os.path.getsize(out) == 0: raise SystemExit("installed app-server is empty")
    meta = {
        "version": args.tag,
        "source": f"https://github.com/{OWNER_REPO}/releases/tag/{args.tag}",
        "asset": asset_name,
        "digest": f"sha256:{expected}",
        "size": os.path.getsize(out),
        "elf_machine": expected_arch,
        "distribution": "official release; not built from Rust"
    }
    if not args.output:
        out_dir = os.path.dirname(out)
        with open(os.path.join(out_dir, "codex-app-server.json"), "w") as f:
            f.write(json.dumps(meta, indent=2) + "\n")
        for name in ("LICENSE", "NOTICE"):
            url = f"https://raw.githubusercontent.com/{OWNER_REPO}/{args.tag}/{name}"
            try:
                body, _ = request(url)
                if body.strip():
                    with open(os.path.join(out_dir, name), "wb") as f:
                        f.write(body)
            except Exception as e:
                print(f"Notice: skipped optional {name} ({e})")

if __name__ == "__main__":
    main()
