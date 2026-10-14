#!/usr/bin/env python3
"""Fetch and verify the official OpenAI Codex app-server release asset.

This deliberately consumes the published binary; it does not build Rust for Android.
"""
from __future__ import annotations
import argparse, hashlib, json, os, shutil, stat, struct, sys, tarfile, tempfile, urllib.request

OWNER_REPO = "openai/codex"
TAG = "rust-v0.162.1"
ASSET = "codex-app-server-aarch64-unknown-linux-musl.tar.gz"
OUT = "app/src/main/assets/codex/codex-app-server"
API = f"https://api.github.com/repos/{OWNER_REPO}/releases/tags/{TAG}"

def request(url):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "Operit-Codex-CI"})
    with urllib.request.urlopen(req) as r: return r.read(), dict(r.headers)

def sha256(path):
    h=hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda:f.read(1024*1024), b""): h.update(b)
    return h.hexdigest()

def elf_arch(path):
    with open(path, "rb") as f:
        h=f.read(20)
    if len(h)<20 or h[:4]!=b"\x7fELF" or h[4]!=2 or h[5]!=1: return None
    return struct.unpack_from("<H", h, 18)[0]

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--repository", default="."); ap.add_argument("--tag", default=TAG); args=ap.parse_args()
    api=f"https://api.github.com/repos/{OWNER_REPO}/releases/tags/{args.tag}"
    raw,_=request(api); release=json.loads(raw)
    asset=next((a for a in release.get("assets",[]) if a.get("name")==ASSET),None)
    if not asset: raise SystemExit(f"missing official release asset {ASSET}")
    expected=(asset.get("digest") or "").removeprefix("sha256:")
    if len(expected)!=64: raise SystemExit("release asset has no sha256 digest")
    root=os.path.abspath(args.repository); out=os.path.join(root,OUT); os.makedirs(os.path.dirname(out),exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        archive=os.path.join(td,ASSET); data,_=request(asset["browser_download_url"]); open(archive,"wb").write(data)
        got=sha256(archive)
        if got != expected: raise SystemExit(f"digest mismatch: {got} != {expected}")
        with tarfile.open(archive,"r:gz") as tar:
            members=[m for m in tar.getmembers() if m.isfile()]
            candidate=next((m for m in members if os.path.basename(m.name)=="codex-app-server"),None)
            if not candidate: raise SystemExit("archive has no codex-app-server")
            if candidate.size<=0: raise SystemExit("empty app-server")
            src=tar.extractfile(candidate); assert src
            tmp=os.path.join(td,"server"); open(tmp,"wb").write(src.read())
            if elf_arch(tmp)!=0xb7: raise SystemExit("app-server is not ELF aarch64")
            shutil.copyfile(tmp,out); os.chmod(out, stat.S_IRUSR|stat.S_IWUSR|stat.S_IXUSR|stat.S_IRGRP|stat.S_IXGRP|stat.S_IROTH|stat.S_IXOTH)
    if os.path.getsize(out)==0: raise SystemExit("installed app-server is empty")
    # Preserve provenance and the upstream notices alongside the generated binary.
    meta={"version":args.tag,"source":f"https://github.com/{OWNER_REPO}/releases/tag/{args.tag}","asset":ASSET,"digest":f"sha256:{expected}","size":os.path.getsize(out),"elf_machine":"aarch64","distribution":"official release; not built from Rust"}
    open(os.path.join(os.path.dirname(out),"codex-app-server.json"),"w").write(json.dumps(meta,indent=2)+"\n")
    for name in ("LICENSE","NOTICE"):
        url=f"https://raw.githubusercontent.com/{OWNER_REPO}/{args.tag}/{name}"
        try: body,_=request(url)
        except Exception: body=(f"Upstream {name} unavailable at {args.tag}; see {url}\n").encode()
        open(os.path.join(os.path.dirname(out),name),"wb").write(body)

if __name__=="__main__": main()
