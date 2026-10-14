#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os, subprocess, sys, tempfile, time

def main():
    p=argparse.ArgumentParser(); p.add_argument("binary"); args=p.parse_args()
    if not os.path.isfile(args.binary) or os.path.getsize(args.binary)==0: raise SystemExit("missing/empty app-server")
    with tempfile.TemporaryDirectory() as home:
        os.makedirs(home, exist_ok=True)
        open(os.path.join(home,"config.toml"),"w").write("[model_providers.operit]\nenv_key = \"OPERIT_CODEX_API_KEY\"\nrequires_openai_auth = false\nwire_api = \"responses\"\nbase_url = \"http://127.0.0.1:9/v1\"\n")
        env=os.environ.copy(); env["CODEX_HOME"]=home; env["OPERIT_CODEX_API_KEY"]="smoke-not-a-real-key"
        proc=subprocess.Popen([args.binary,"--listen","stdio://"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
        def send(x): proc.stdin.write(json.dumps(x)+"\n"); proc.stdin.flush()
        send({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"operit-ci","version":"0"},"capabilities":{}}})
        deadline=time.time()+15; lines=[]
        while time.time()<deadline:
            line=proc.stdout.readline()
            if line:
                lines.append(line);
                if json.loads(line).get("id")==1: break
            elif proc.poll() is not None: break
        send({"jsonrpc":"2.0","method":"initialized","params":{}})
        time.sleep(.5); proc.terminate(); proc.wait(timeout=5)
        if not any(json.loads(x).get("id")==1 for x in lines):
            raise SystemExit("initialize handshake failed; mock Responses was intentionally not called")
        print("Codex app-server stdio initialize + initialized handshake passed; no real model/API request made")
if __name__=="__main__": main()
