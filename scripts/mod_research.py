"""Low-output catalogue client. Python standard library only; see --help."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, quote
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler
import uuid
import atexit
import ctypes
import socket
import subprocess
import time

PREFIX = '/internal/mod-catalogue/v1'
MAX_BODY = 1_048_576
MAX_RESPONSE = 32 * MAX_BODY
PRIMARY = [('1.20.1', 'FORGE'), ('1.21.1', 'NEOFORGE'), ('1.7.10', 'FORGE'), ('1.12.2', 'FORGE')]


def config_path():
    if os.environ.get('MODS_API_CONFIG'):
        return Path(os.environ['MODS_API_CONFIG'])
    return Path(os.environ.get('LOCALAPPDATA') or Path.home()) / 'Wolfsnetz' / 'mod-catalogue' / 'connection.json'


def protect_token(data, decrypt=False):
    if os.name != 'nt':
        raise ClientError('Saved credentials use Windows DPAPI. On other systems set MODS_API_TOKEN in the process.')
    class Blob(ctypes.Structure):
        _fields_ = [('size', ctypes.c_ulong), ('data', ctypes.POINTER(ctypes.c_byte))]
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    output = Blob()
    crypt = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_ulong, ctypes.POINTER(Blob)]
    function.restype = ctypes.c_int
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise ClientError('Cannot access the saved credential for this Windows user.')
    try:
        return ctypes.string_at(output.data, output.size)
    finally:
        kernel.LocalFree(ctypes.cast(output.data, ctypes.c_void_p))


class ClientError(Exception):
    def __init__(self, message, **details):
        super().__init__(message)
        self.details = details


def compact(data):
    return json.dumps(data, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def emit(data):
    print(compact(data))


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write(path, data, exclusive=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Work files have no credentials. Never overwrite another job on checkout/new.
    with path.open('x' if exclusive else 'w', encoding='utf-8') as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def identifier(value):
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,99}', value):
        raise ClientError('Use a lowercase ID, maximum 100 characters.')
    return value


def origin(value):
    parts = urlsplit(value)
    if (parts.scheme not in ('http', 'https') or parts.hostname not in ('127.0.0.1', 'localhost', '::1')
            or parts.username is not None or parts.password is not None or parts.path not in ('', '/')
            or parts.query or parts.fragment):
        raise ClientError('Use a localhost origin; reach the Pi through an SSH tunnel.')
    _ = parts.port
    return value.rstrip('/')


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base, token=None, ssh=None):
        self.base = origin(base)
        self.token = token
        self.ssh = ssh
        self.tunnel = None
        self.opener = build_opener(ProxyHandler({}), NoRedirects())

    def connect(self):
        if not self.ssh:
            return
        endpoint = urlsplit(self.base)
        def listening():
            try:
                with socket.create_connection((endpoint.hostname, endpoint.port), timeout=0.3):
                    return True
            except OSError:
                return False
        if listening():
            return
        if self.tunnel is None:
            if not re.fullmatch(r'[a-zA-Z0-9_.-]+@[a-zA-Z0-9_.-]+', self.ssh):
                raise ClientError('Invalid SSH destination in saved connection.')
            self.tunnel = subprocess.Popen(['ssh', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes', '-o', 'ConnectTimeout=8',
                '-N', '-L', f'127.0.0.1:{endpoint.port}:127.0.0.1:8081', self.ssh], stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            atexit.register(self.close)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline and self.tunnel.poll() is None:
            if listening():
                return
            time.sleep(0.1)
        self.close()
        raise ClientError('SSH tunnel failed. Verify SSH key access to the saved Pi destination.')

    def close(self):
        if self.tunnel is not None and self.tunnel.poll() is None:
            self.tunnel.terminate()
            try:
                self.tunnel.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.tunnel.kill()
                self.tunnel.wait(timeout=3)

    def call(self, path, body=None):
        token = os.environ.get('MODS_API_TOKEN', '') or self.token
        if not token:
            raise ClientError('Set MODS_API_TOKEN in the process environment. Do not put secrets in arguments.')
        self.connect()
        data = None if body is None else compact(body).encode('utf-8')
        if data is not None and len(data) > MAX_BODY:
            raise ClientError('Batch exceeds 1 MiB. Split the research job.')
        request = Request(self.base + PREFIX + path, data=data, headers={
            'Authorization': 'Bearer ' + token, 'Accept': 'application/json', 'Content-Type': 'application/json'})
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE:
                    raise ClientError('Response too large; use a smaller page.')
                return json.loads(raw)
        except HTTPError as error:
            message = {401: 'Research token rejected.', 403: 'Access denied.',
                       404: 'API unavailable, disabled, wrong port, or record not found.',
                       409: 'Revision or request conflict; checkout again before editing.',
                       413: 'Batch exceeds 1 MiB.'}.get(error.code, 'Catalogue request failed.')
            if error.code in (400, 409):
                try:
                    message = str(json.loads(error.read(8192)).get('message', message))[:800].replace(token, '[redacted]')
                except (ValueError, UnicodeError):
                    pass
            error.close()
            raise ClientError(message, status=error.code) from None
        except (URLError, TimeoutError, OSError):
            raise ClientError('Cannot reach the private API. Check the server, tunnel, and local port.') from None

    def get(self, family_id, missing=False):
        try:
            return self.call('/families/' + quote(identifier(family_id), safe=''))
        except ClientError as error:
            if missing and error.details.get('status') == 404:
                # Distinguish an absent record from an absent API.
                self.call('/targets')
                return None
            raise


def support(version, loader):
    return dict(target=dict(minecraftVersion=version, loader=loader), availability='UNKNOWN', maintenance='UNKNOWN',
                notes='', checkedAt=None, evidenceIds=[], releases=[])


def project(project_id, name):
    return dict(id=identifier(project_id), name=name, description='', listings=[],
                support=[support(v, loader) for v, loader in PRIMARY], relationships=[])


def family(family_id, name):
    return dict(id=identifier(family_id), name=name, description='', aliases=[], tags=[], published=False, archived=False,
                projects=[project(family_id, name)], evidence=[], comparisons=[])


def brief(entry):
    data = entry['family']
    return dict(id=data['id'], revision=entry['revision'], name=data['name'], published=data['published'], archived=data['archived'],
                projects=[p['id'] for p in data['projects']], sources=len(data['evidence']))


def pointer(data, path):
    if path == '':
        return data
    if not path.startswith('/'):
        raise ClientError('JSON pointers start with /. Example: /projects/0/support')
    for part in path[1:].split('/'):
        key = part.replace('~1', '/').replace('~0', '~')
        data = data[int(key)] if isinstance(data, list) else data[key]
    return data


def set_pointer(data, path, value):
    if not path or path == '/id':
        raise ClientError('A patch must address a family field; the family ID is fixed.')
    parent_path, _, key = path.rpartition('/')
    parent = pointer(data, parent_path)
    key = key.replace('~1', '/').replace('~0', '~')
    if isinstance(parent, list):
        if key == '-':
            parent.append(copy.deepcopy(value))
        else:
            parent[int(key)] = copy.deepcopy(value)
    else:
        if key not in parent:
            raise ClientError('Unknown field in patch: ' + path)
        parent[key] = copy.deepcopy(value)


def delta(before, after, path=''):
    if before == after:
        return []
    if isinstance(before, dict) and isinstance(after, dict):
        result = []
        for key in sorted(before.keys() | after.keys()):
            result.extend(delta(before.get(key), after.get(key), path + '/' + key))
        return result
    # Lists are one field: avoid filling the conversation with individual source/release changes.
    return [path or '/']


def entries(path):
    document = read(path)
    if 'family' in document:  # Single saved entry from checkout.
        return [document]
    if isinstance(document.get('entries'), list):  # Multi-family research/export file.
        return document['entries']
    raise ClientError('Use a checkout/new file or {"entries":[{"revision":0,"family":{...}}]}.')


def upload(client, path, reason):
    items = entries(path)
    if not 1 <= len(items) <= 20:
        raise ClientError('Use 1–20 families per upload.')
    if not reason.strip() or len(reason) > 2000:
        raise ClientError('Provide a change reason, maximum 2000 characters.')
    changes, unchanged, committed = [], [], []
    ids = set()
    for entry in items:
        data, revision = entry['family'], entry['revision']
        family_id = identifier(data['id'])
        if family_id in ids:
            raise ClientError('Duplicate family in research file: ' + family_id)
        ids.add(family_id)
        if type(revision) is not int or revision < 0:
            raise ClientError('Revision must be a non-negative integer.')
        current = client.get(family_id, missing=True)
        if current is not None and current['family'] == data:
            unchanged.append(dict(id=family_id, revision=current['revision']))
            continue
        actual = 0 if current is None else current['revision']
        if actual != revision:
            raise ClientError('Revision conflict. Checkout and reconcile before uploading.', id=family_id, expected=revision, actual=actual)
        changes.append(dict(expectedRevision=revision, family=data))
    if not changes:
        return dict(ok=True, changed=[], unchanged=unchanged)
    # Deterministic retry ID: preserves server idempotency if the response is lost.
    payload = dict(reason=reason, changes=changes)
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
    batch = dict(requestId='research-' + digest, **payload)
    client.call('/batches?dryRun=true', batch)
    result = client.call('/batches', batch)
    for saved in result['entries']:
        family_id = saved['family']['id']
        try:
            current = client.get(family_id)
        except ClientError:
            raise ClientError('Upload committed; readback unavailable. Rerun the same upload to check without duplicating it.', committed=True) from None
        if current['family'] != saved['family'] or current['revision'] != saved['revision']:
            raise ClientError('Upload committed, but a subsequent update changed the entry. Inspect before continuing.', committed=True, id=family_id)
        committed.append(dict(id=family_id, revision=saved['revision']))
    return dict(ok=True, verified=True, changed=committed, unchanged=unchanged)


def parser():
    p = argparse.ArgumentParser(description='Compact catalogue research client. Commands print one JSON receipt; full records stay in files.')
    p.add_argument('--base', help='Local origin override (requires MODS_API_TOKEN); otherwise use saved Pi connection or environment')
    commands = p.add_subparsers(dest='command', required=True)
    commands.add_parser('status', help='Check private API access; compact target list')
    configure = commands.add_parser('configure', help='Save the process token encrypted for this Windows user and enable automatic SSH')
    configure.add_argument('--ssh', required=True)
    configure.add_argument('--local-port', type=int, default=18081)
    find = commands.add_parser('find', help='Find possible duplicates; compact results, drafts/archives included')
    find.add_argument('query'); find.add_argument('--limit', type=int, default=5); find.add_argument('--offset', type=int, default=0)
    checkout = commands.add_parser('checkout', help='Save full entry to a file without printing it')
    checkout.add_argument('id'); checkout.add_argument('--out', required=True)
    new = commands.add_parser('new', help='Generate a ready-to-fill draft, including four unknown support targets')
    new.add_argument('id'); new.add_argument('--name', required=True); new.add_argument('--out', required=True)
    show = commands.add_parser('show', help='Read only the needed family field from a working file')
    show.add_argument('file'); show.add_argument('--pointer', help='Family JSON pointer; omit for a compact summary')
    patch = commands.add_parser('patch', help='Apply {"/family/pointer":value} field replacements; /array/- appends')
    patch.add_argument('file'); patch.add_argument('--changes', required=True)
    push = commands.add_parser('upload', help='Check revisions, skip no-ops, dry-run, commit, read back; one compact receipt')
    push.add_argument('file'); push.add_argument('--reason', required=True)
    template = commands.add_parser('template', help='Print a small object template for an append/replace patch')
    template.add_argument('type', choices=['project', 'support', 'evidence', 'listing', 'relationship', 'comparison', 'release'])
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    if args.command == 'configure':
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+@[a-zA-Z0-9_.-]+', args.ssh) or not 1024 <= args.local_port <= 65535:
            raise ClientError('Use user@host and a local port in 1024–65535.')
        token = os.environ.get('MODS_API_TOKEN', '')
        if len(token) < 32:
            raise ClientError('Provision a random token of at least 32 characters in MODS_API_TOKEN first.')
        config = dict(base=f'http://127.0.0.1:{args.local_port}', ssh=args.ssh, tokenDpapi=protect_token(token.encode()).hex())
        write(config_path(), config)
        emit(dict(ok=True, connection='raspberry-pi', encrypted=True, ssh=args.ssh))
        return 0
    explicit = args.base or os.environ.get('MODS_API_BASE')
    network = args.command in ('status', 'find', 'checkout', 'upload')
    config = read(config_path()) if network and not explicit and config_path().exists() else {}
    base = args.base or os.environ.get('MODS_API_BASE') or config.get('base', 'http://127.0.0.1:18081')
    token = protect_token(bytes.fromhex(config['tokenDpapi']), decrypt=True).decode() if config else None
    client = Client(base, token=token, ssh=config.get('ssh'))
    if args.command == 'status':
        targets = client.call('/targets')
        result = dict(ok=True, base=client.base, targets=[t['minecraftVersion'] + ':' + t['loader'] for t in targets])
    elif args.command == 'find':
        if not 1 <= args.limit <= 20 or args.offset < 0:
            raise ClientError('Use limit 1–20 and offset >=0.')
        page = client.call('/families?' + urlencode(dict(q=args.query, includeArchived='true', limit=args.limit, offset=args.offset)))
        result = dict(total=page['total'], offset=page['offset'], entries=[brief(e) for e in page['entries']])
    elif args.command == 'checkout':
        entry = client.get(args.id)
        write(args.out, entry, exclusive=True)
        result = dict(file=str(Path(args.out)), **brief(entry))
    elif args.command == 'new':
        entry = dict(revision=0, family=family(args.id, args.name))
        write(args.out, entry, exclusive=True)
        result = dict(file=str(Path(args.out)), **brief(entry))
    elif args.command in ('show', 'patch'):
        entry = read(args.file)
        data = entry['family']
        if args.command == 'show':
            result = pointer(data, args.pointer) if args.pointer is not None else brief(entry)
        else:
            changes = read(args.changes)
            if not isinstance(changes, dict):
                raise ClientError('Patch must be an object mapping family JSON pointers to values.')
            before = copy.deepcopy(data)
            for path, value in changes.items():
                set_pointer(data, path, value)
            changed = delta(before, data)
            write(args.file, entry)
            result = dict(file=args.file, changed=changed)
    elif args.command == 'upload':
        result = upload(client, args.file, args.reason)
    else:
        templates = {
            'project': project('project-id', 'Project name'), 'support': support('1.20.1', 'FORGE'),
            'evidence': dict(id='source-id', url='', claim='', summary='', checkedAt='', uncertain=False),
            'listing': dict(platform='MODRINTH', platformId='', url=''),
            'relationship': dict(relatedProjectId='', type='REQUIRED_DEPENDENCY', target=None, notes='', evidenceIds=[]),
            'comparison': dict(id='comparison-id', left=dict(projectId='', target=dict(minecraftVersion='1.20.1', loader='FORGE')),
                               right=dict(projectId='', target=dict(minecraftVersion='1.21.1', loader='NEOFORGE')),
                               parity='UNKNOWN', notes='', checkedAt=None, evidenceIds=[]),
            'release': dict(platformReleaseId='', platform='MODRINTH', modVersion='', channel='RELEASE', publishedAt='', url='')}
        result = templates[args.type]
    emit(result)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except ClientError as error:
        emit(dict(ok=False, error=str(error), **error.details))
        sys.exit(1)
    except (ValueError, KeyError, IndexError, TypeError, OSError):
        emit(dict(ok=False, error='Invalid input, inaccessible file, or existing output. Check paths, JSON, and --help.'))
        sys.exit(1)
