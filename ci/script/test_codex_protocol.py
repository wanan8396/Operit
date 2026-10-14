#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os, selectors, subprocess, sys, tempfile, time

def main():
    p=argparse.ArgumentParser(); p.add_argument("binary"); args=p.parse_args()
    if not os.path.isfile(args.binary) or os.path.getsize(args.binary)==0: raise SystemExit("missing/empty app-server")
    with tempfile.TemporaryDirectory() as home:
        os.makedirs(home, exist_ok=True)
        config="[model_providers.operit]\nenv_key = \"OPERIT_CODEX_API_KEY\"\nrequires_openai_auth = false\nwire_api = \"responses\"\nbase_url = \"http://127.0.0.1:9/v1\"\n"
        assert 'env_key = \"OPERIT_CODEX_API_KEY\"' in config and "requires_openai_auth = false" in config
        open(os.path.join(home,"config.toml"),"w").write(config)
        env=os.environ.copy(); env["CODEX_HOME"]=home; env["OPERIT_CODEX_API_KEY"]="smoke-not-a-real-key"
        proc=subprocess.Popen([args.binary,"--listen","stdio://"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
        def send(x): proc.stdin.write(json.dumps(x)+"\n"); proc.stdin.flush()
        send({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"operit-ci","version":"0"},"capabilities":{}}})
        selector=selectors.DefaultSelector(); selector.register(proc.stdout,selectors.EVENT_READ)
        deadline=time.monotonic()+15; lines=[]; response=None
        while time.monotonic()<deadline:
            events=selector.select(max(0,deadline-time.monotonic()))
            if not events: break
            line=proc.stdout.readline()
            if not line: break
            msg=json.loads(line); lines.append(msg)
            if msg.get("id")==1: response=msg; break
        if not response or "error" in response or not isinstance(response.get("result"),dict):
            proc.kill(); proc.wait(); raise SystemExit("initialize failed/error; no API request made")
        send({"jsonrpc":"2.0","method":"initialized","params":{}})
        send({"jsonrpc":"2.0","id":2,"method":"thread/start","params":{"model":"gpt-5-codex","model_provider":"operit","approvalPolicy":"never","sandbox":"read-only"}})
        deadline=time.monotonic()+15; got_thread=False
        while time.monotonic()<deadline:
            if not selector.select(max(0,deadline-time.monotonic())): break
            line=proc.stdout.readline()
            if not line: break
            msg=json.loads(line); lines.append(msg)
            if msg.get("id")==2:
                if "error" in msg: proc.kill(); proc.wait(); raise SystemExit("thread/start rejected configured provider")
                got_thread=True; break
        proc.terminate(); proc.wait(timeout=5)
        if not got_thread: raise SystemExit("thread/start timed out")
        print("initialize/initialized/thread-start passed with operit provider; no paid API request")
        print("Codex app-server stdio initialize + initialized handshake passed; no real model/API request made")
if __name__=="__main__": main()
