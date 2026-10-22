import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

spec = importlib.util.spec_from_file_location('mod_research', Path(__file__).parents[1] / 'mod_research.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class Store:
    """Small API double to exercise client failure boundaries, not server validation."""
    def __init__(self):
        self.data = {}
        self.requests = []
        self.fail_validation = False
        self.fail_readback = False
        self.saved = False

    def get(self, family_id, missing=False):
        if self.saved and self.fail_readback:
            raise tool.ClientError('Connection unavailable')
        return copy.deepcopy(self.data.get(family_id))

    def call(self, path, body):
        self.requests.append((path, copy.deepcopy(body)))
        if path.endswith('dryRun=true'):
            if self.fail_validation:
                raise tool.ClientError('Missing evidence', status=400)
            return {'entries': []}
        result = []
        for change in body['changes']:
            entry = dict(revision=change['expectedRevision'] + 1, family=copy.deepcopy(change['family']))
            self.data[entry['family']['id']] = entry
            result.append(entry)
        self.saved = True
        return {'entries': result}


class ResearchClientTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / 'work.json'
        self.entry = dict(revision=0, family=tool.family('example', 'Example'))
        self.entry['family']['description'] = 'Synthetic test only'
        self.entry['family']['projects'][0]['description'] = 'Synthetic project only'
        tool.write(self.file, self.entry)

    def test_upload_validates_commits_and_verifies_with_small_receipt(self):
        store = Store()
        result = tool.upload(store, self.file, 'Research test')
        self.assertTrue(result['verified'])
        self.assertEqual([{'id': 'example', 'revision': 1}], result['changed'])
        self.assertEqual(['/batches?dryRun=true', '/batches'], [path for path, _ in store.requests])
        self.assertLess(len(tool.compact(result)), len(tool.compact(self.entry)) // 3)
        self.assertNotIn('description', tool.compact(result))

    def test_repeat_upload_is_a_noop_even_if_first_response_was_lost(self):
        store = Store()
        tool.upload(store, self.file, 'Research test')
        store.requests.clear()
        result = tool.upload(store, self.file, 'Research test')
        self.assertEqual([], result['changed'])
        self.assertEqual([{'id': 'example', 'revision': 1}], result['unchanged'])
        self.assertEqual([], store.requests)

    def test_conflict_detected_before_any_batch_write(self):
        store = Store()
        store.data['example'] = dict(revision=2, family=dict(self.entry['family'], name='Changed by someone else'))
        with self.assertRaises(tool.ClientError) as context:
            tool.upload(store, self.file, 'Research test')
        self.assertEqual(2, context.exception.details['actual'])
        self.assertEqual([], store.requests)

    def test_validation_failure_never_commits(self):
        store = Store()
        store.fail_validation = True
        with self.assertRaises(tool.ClientError):
            tool.upload(store, self.file, 'Research test')
        self.assertEqual(1, len(store.requests))
        self.assertEqual({}, store.data)

    def test_readback_failure_reports_committed_and_retry_does_not_duplicate(self):
        store = Store()
        store.fail_readback = True
        with self.assertRaises(tool.ClientError) as context:
            tool.upload(store, self.file, 'Research test')
        self.assertTrue(context.exception.details['committed'])
        store.fail_readback = False
        self.assertEqual([], tool.upload(store, self.file, 'Research test')['changed'])
        self.assertEqual(1, store.data['example']['revision'])

    def test_pointer_patch_preserves_other_fields_and_can_append_evidence(self):
        original = copy.deepcopy(self.entry['family'])
        tool.set_pointer(self.entry['family'], '/projects/0/description', 'Researched description')
        tool.set_pointer(self.entry['family'], '/evidence/-', {'id': 'source'})
        self.assertEqual(original['projects'][0]['support'], self.entry['family']['projects'][0]['support'])
        self.assertEqual({'id': 'source'}, tool.pointer(self.entry['family'], '/evidence/0'))
        self.assertEqual(['/evidence', '/projects'], tool.delta(original, self.entry['family']))

    def test_failed_patch_leaves_original_file_unchanged(self):
        changes = Path(self.temp.name) / 'patch.json'
        tool.write(changes, {'/description': 'Should not persist', '/unknown': 'bad field'})
        with self.assertRaises(tool.ClientError):
            tool.main(['patch', str(self.file), '--changes', str(changes)])
        self.assertEqual(self.entry, tool.read(self.file))

    def test_new_refuses_to_overwrite_an_existing_research_job(self):
        with self.assertRaises(FileExistsError):
            tool.main(['new', 'example', '--name', 'New example', '--out', str(self.file)])
        self.assertEqual(self.entry, tool.read(self.file))

    def test_local_commands_work_without_credentials_and_show_only_requested_fields(self):
        with patch.dict('os.environ', {}, clear=True), contextlib.redirect_stdout(io.StringIO()) as output:
            tool.main(['show', str(self.file), '--pointer', '/projects/0/id'])
        self.assertEqual('example', json.loads(output.getvalue()))

    def test_only_loopback_origins_and_no_credentials_in_urls(self):
        for address in ['http://example.com', 'http://localhost.example.com', 'http://secret@localhost',
                        'http://localhost/path', 'http://localhost/?token=secret', 'http://127.0.0.1#fragment']:
            with self.assertRaises(tool.ClientError):
                tool.Client(address)
        self.assertEqual('http://127.0.0.1:18081', tool.Client('http://127.0.0.1:18081/').base)

    @unittest.skipUnless(os.name == 'nt', 'DPAPI is Windows-specific')
    def test_saved_connection_encrypts_token_and_round_trips_for_current_user(self):
        token = 'test-only-' + 'a' * 64
        saved = Path(self.temp.name) / 'connection.json'
        with patch.dict('os.environ', {'MODS_API_TOKEN': token, 'MODS_API_CONFIG': str(saved)}), contextlib.redirect_stdout(io.StringIO()) as output:
            tool.main(['configure', '--ssh', 'pi@pi'])
        self.assertNotIn(token, output.getvalue())
        self.assertNotIn(token, saved.read_text())
        config = tool.read(saved)
        self.assertEqual(token, tool.protect_token(bytes.fromhex(config['tokenDpapi']), decrypt=True).decode())

    def test_http_redirect_does_not_forward_the_token(self):
        hits = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                hits.append(self.path)
                self.send_response(302)
                self.send_header('Location', '/redirect-target')
                self.end_headers()

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with patch.dict('os.environ', {'MODS_API_TOKEN': 'test-secret'}):
                with self.assertRaises(tool.ClientError) as context:
                    tool.Client('http://127.0.0.1:' + str(server.server_port)).call('/targets')
            self.assertNotIn('test-secret', str(context.exception))
            self.assertEqual(['/internal/mod-catalogue/v1/targets'], hits)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    unittest.main()
