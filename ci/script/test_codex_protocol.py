#!/usr/bin/env python3
"""Exercise official app-server against an isolated API-key provider; no model request."""
import argparse
import json
import os
import queue
import subprocess
import tempfile
import threading
import time

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('binary')
    args = parser.parse_args()
    binary = os.path.abspath(args.binary)
    with tempfile.TemporaryDirectory() as home:
        with open(os.path.join(home, 'config.toml'), 'w') as config:
            config.write('''model = "gpt-5-codex"
model_provider = "operit"
[model_providers.operit]
name = "Operit CI"
env_key = "OPERIT_CODEX_API_KEY"
requires_openai_auth = false
wire_api = "responses"
base_url = "http://127.0.0.1:9/v1"
''')
        env = os.environ.copy()
        env.update(CODEX_HOME=home, OPERIT_CODEX_API_KEY='smoke-not-a-real-key')
        messages = queue.Queue()
        with tempfile.TemporaryFile(mode='w+t') as stderr:
            proc = subprocess.Popen([binary, '--listen', 'stdio://'], stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=stderr, text=True, env=env, cwd=home)
            def reader():
                try:
                    for line in proc.stdout:
                        messages.put(json.loads(line))
                except Exception as error:
                    messages.put(error)
                finally:
                    messages.put(EOFError('app-server stdout closed'))
            threading.Thread(target=reader, daemon=True).start()
            def send(message):
                proc.stdin.write(json.dumps(message) + '\n')
                proc.stdin.flush()
            def reply(request_id):
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    message = messages.get(timeout=max(.01, deadline - time.monotonic()))
                    if isinstance(message, Exception):
                        raise message
                    if message.get('id') == request_id:
                        if 'error' in message:
                            raise RuntimeError(json.dumps(message['error']))
                        return message['result']
                raise TimeoutError('app-server reply timed out')
            try:
                send({'id': 1, 'method': 'initialize', 'params': {
                    'clientInfo': {'name': 'operit-ci', 'version': '1'},
                    'capabilities': {'experimentalApi': False}}})
                assert isinstance(reply(1), dict)
                send({'method': 'initialized'})
                send({'id': 2, 'method': 'thread/start', 'params': {
                    'model': 'gpt-5-codex', 'modelProvider': 'operit',
                    'cwd': home, 'ephemeral': True, 'approvalPolicy': 'on-request', 'sandbox': 'read-only'}})
                result = reply(2)
                assert result['thread']['id'], result
                assert result.get('modelProvider', 'operit') == 'operit', result
                print('PASS: real initialize/initialized/thread/start; operit API-key provider; no OAuth or paid inference')
            except Exception:
                stderr.flush(); stderr.seek(0)
                print(stderr.read()[-8000:])
                raise
            finally:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill(); proc.wait(timeout=5)

if __name__ == '__main__':
    main()
