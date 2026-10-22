"""Exercise the compact client against a disposable packaged app (no live database).

py scripts/tests/smoke_mod_research.py --jar build/libs/Webserver-0.0.1-SNAPSHOT.jar
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import hashlib


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    jar = Path(args.jar).resolve(strict=True)
    tool = root / 'scripts/mod_research.py'
    website_port, research_port = free_port(), free_port()
    while website_port == research_port:
        research_port = free_port()
    token = secrets.token_hex(32)
    env = dict(os.environ, MODS_API_ENABLED='true', MODS_API_PORT=str(research_port),
               MODS_API_TOKEN=token, MODS_API_TOKEN_SHA256=hashlib.sha256(token.encode()).hexdigest(),
               MODS_API_BASE=f'http://127.0.0.1:{research_port}', BOOTSTRAP_ADMIN_PASSWORD=secrets.token_hex(24))
    with tempfile.TemporaryDirectory(prefix='mod-research-smoke-') as directory:
        work = Path(directory)
        env['SPRING_DATASOURCE_URL'] = 'jdbc:sqlite:' + str(work / 'catalogue.sqlite')
        with (work / 'app.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([args.java, '-jar', str(jar), f'--server.port={website_port}', '--server.address=127.0.0.1'],
                                       env=env, cwd=root, stdout=log, stderr=log,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

            def invoke(*command, expected=0):
                result = subprocess.run([sys.executable, str(tool), *map(str, command)], env=env, cwd=root,
                                        text=True, encoding='utf-8', capture_output=True, timeout=45)
                if result.returncode != expected:
                    raise AssertionError(f'Client {command[0]}: {result.stdout} {result.stderr}')
                return json.loads(result.stdout)

            try:
                for _ in range(100):
                    if process.poll() is not None:
                        raise AssertionError('Disposable application exited before startup.')
                    result = subprocess.run([sys.executable, str(tool), 'status'], env=env, cwd=root,
                                            capture_output=True, timeout=35)
                    if result.returncode == 0:
                        break
                    time.sleep(0.2)
                else:
                    raise AssertionError('Disposable application did not become ready.')
                job, changes = work / 'job.json', work / 'changes.json'
                invoke('new', 'research-smoke', '--name', 'Synthetic research smoke fixture', '--out', job)
                changes.write_text(json.dumps({'/description': 'Synthetic fixture, not a real mod.',
                                               '/projects/0/description': 'Synthetic project for client validation.'}), encoding='utf-8')
                invoke('patch', job, '--changes', changes)
                saved = invoke('upload', job, '--reason', 'Validate the compact research client with synthetic data')
                assert saved['verified'] and saved['changed'][0]['revision'] == 1
                repeat = invoke('upload', job, '--reason', 'Validate the compact research client with synthetic data')
                assert not repeat['changed'] and repeat['unchanged'][0]['revision'] == 1
                found = invoke('find', 'Synthetic research')
                assert found['total'] == 1 and found['entries'][0]['id'] == 'research-smoke'
                checkout = work / 'checkout.json'
                invoke('checkout', 'research-smoke', '--out', checkout)
                changes.write_text(json.dumps({'/description': 'Updated synthetic fixture.'}), encoding='utf-8')
                invoke('patch', checkout, '--changes', changes)
                updated = invoke('upload', checkout, '--reason', 'Test a selected field update')
                assert updated['changed'][0]['revision'] == 2
                stale = invoke('upload', job, '--reason', 'Must not overwrite a newer edit', expected=1)
                assert stale['actual'] == 2
                baseline = json.loads(checkout.read_text(encoding='utf-8'))
                original_chars = len(json.dumps(baseline, indent=2))
                receipt_chars = len(json.dumps(updated, separators=(',', ':')))
                print(json.dumps(dict(ok=True, checks=['new', 'patch', 'upload', 'readback', 'no-op retry', 'find', 'checkout', 'update', 'stale conflict'],
                                      full_record_characters=original_chars, receipt_characters=receipt_chars,
                                      reduction_percent=round(100 * (1 - receipt_chars / original_chars))), separators=(',', ':')))
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


if __name__ == '__main__':
    main()
