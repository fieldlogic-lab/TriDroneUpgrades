import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import subprocess
import base64
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('runner', Path(__file__).with_name('runner.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
REQUEST = '12345678-1234-1234-1234-123456789abc'
PLAN = {'summary': 'Fix label', 'acceptanceCriteria': ['Correct label'], 'allowedPaths': ['app/src/main/res/values/strings.xml']}

class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = patch.dict(os.environ, PHASE='develop', REQUEST_ID=REQUEST, REQUEST_ISSUE='12',
                              PLAN_COMMENT_ID='34', GITHUB_REF='refs/heads/main', GITHUB_RUN_ID='99')
        self.env.start()
        self.addCleanup(self.env.stop)
        self.data = patch.object(runner, 'DATA', Path(self.temp.name))
        self.data.start()
        self.addCleanup(self.data.stop)
        self.metadata = dict(version=1, requestId=REQUEST, type='plan', phase='plan',
                             status='awaiting_scope_approval', plan=PLAN)
        self.comment = {'id': 34, 'user': {'login': 'github-actions[bot]'},
                        'body': runner.PREFIX + json.dumps(self.metadata) + ' -->\nFix label'}
        os.environ['PLAN_SHA256'] = hashlib.sha256(self.comment['body'].encode()).hexdigest()
        self.issue = {'state': 'open', 'body': '<!-- solutionshq-request:' + json.dumps(dict(
            version=1, requestId=REQUEST, project='hydrographic-usv')) + ' -->\nFix label'}

    def intake(self, comments=None):
        with patch.object(runner, 'api', return_value=self.issue), \
             patch.object(runner, 'comments', return_value=[self.comment] if comments is None else comments), \
             patch.object(runner, 'git', return_value='a' * 40), patch.object(runner, 'emit'), \
             patch.object(runner, 'claim_request'):
            runner.intake()

    def test_valid_exact_approval(self):
        self.intake()
        self.assertEqual(json.loads((runner.DATA / 'context.json').read_text())['plan'], PLAN)

    def test_reject_modified_plan_after_approval(self):
        self.comment['body'] += 'changed'
        with self.assertRaises(AssertionError):
            self.intake()

    def test_reject_forged_bot_plan(self):
        self.comment['user']['login'] = 'attacker'
        with self.assertRaises(AssertionError):
            self.intake()

    def test_reject_plan_from_other_issue(self):
        with self.assertRaises(AssertionError):
            self.intake([])

    def test_reject_wrong_request(self):
        self.issue['body'] = self.issue['body'].replace(REQUEST, 'different')
        with self.assertRaises(AssertionError):
            self.intake()

    def test_duplicate_phase_is_single_use(self):
        duplicate = {'user': {'login': 'github-actions[bot]'}, 'body': runner.PREFIX +
                     json.dumps(dict(requestId=REQUEST, phase='develop', status='failed')) + ' -->'}
        with self.assertRaises(AssertionError):
            self.intake([self.comment, duplicate])

    def test_forbidden_paths(self):
        for path in (None, '../app/src/a', '/app/src/a', 'app/src/../../.github/workflows/x',
                     'app/src\\evil', '.github/workflows/a.yml', 'build.gradle.kts', 'docs/AGENTS.md',
                     'docs/.codex/config.toml', 'app/src/x\ncommand'):
            self.assertFalse(runner.allowed_path(path), path)
        self.assertTrue(runner.allowed_path('app/src/main/java/com/example/App.kt'))

    def test_canonical_uuid_binding_rejects_other_issue(self):
        with patch.object(runner, 'api', side_effect=[{'object': {'type': 'tag', 'sha': 'b' * 40}},
                {'message': json.dumps({'requestId': REQUEST, 'issue': 999})}]), self.assertRaises(AssertionError):
            runner.claim_request('a' * 40)

    def test_phase_claim_uses_atomic_ref(self):
        with patch.object(runner, 'api', side_effect=[{'object': {'type': 'tag', 'sha': 'b' * 40}},
                {'message': json.dumps({'requestId': REQUEST, 'issue': 12})}, {}]) as api:
            runner.claim_request('a' * 40)
        self.assertEqual(api.call_args_list[-1].args, ('/git/refs', 'POST', {
            'ref': 'refs/tags/solutionshq-phases/' + REQUEST + '/develop', 'sha': 'a' * 40}))

    def test_failed_push_never_exposes_encoded_or_raw_secret_in_exception(self):
        token = 'test-secret-never-log'
        encoded = base64.b64encode(('x-access-token:' + token).encode()).decode()
        def fail(command, **kwargs):
            self.assertNotIn(token, str(command))
            self.assertNotIn(encoded, str(command))
            self.assertEqual(kwargs['env']['GIT_CONFIG_VALUE_0'], 'AUTHORIZATION: basic ' + encoded)
            raise subprocess.CalledProcessError(1, command)
        with patch.dict(os.environ, GH_TOKEN=token), patch.object(runner.subprocess, 'run', side_effect=fail), \
             self.assertRaises(subprocess.CalledProcessError) as captured:
            runner.trusted_push('codex/hq-test')
        self.assertNotIn(token, str(captured.exception))
        self.assertNotIn(encoded, str(captured.exception))

    def test_plan_restricts_exact_files(self):
        with self.assertRaises(AssertionError):
            runner.validate_plan(dict(PLAN, allowedPaths=['app/src/**']))

    def test_publisher_rejects_unapproved_manifest_before_git(self):
        (runner.DATA / 'context.json').write_text(json.dumps(dict(base='a' * 40, plan=PLAN)))
        (runner.DATA / 'result.json').write_text(json.dumps(dict(base='a' * 40,
            files=[dict(path='.github/workflows/evil.yml', content='aGVsbG8=')])))
        with patch.object(runner, 'git', return_value='a' * 40), self.assertRaises(AssertionError):
            runner.publish()

    def test_stale_qa_rejected(self):
        os.environ.update(HEAD_SHA='a' * 40, PR_NUMBER='1', BUILD_EXIT='0')
        (runner.DATA / 'qa.json').write_text(json.dumps(dict(status='pass', findings=[], summary='Reviewed')))
        with patch.object(runner, 'api', return_value={'head': {'sha': 'b' * 40}}), \
             self.assertRaises(AssertionError):
            runner.qa_publish()

    def test_failed_build_cannot_be_qa_pass(self):
        os.environ.update(HEAD_SHA='a' * 40, PR_NUMBER='1', BUILD_EXIT='1')
        (runner.DATA / 'qa.json').write_text(json.dumps(dict(status='pass', findings=[], summary='Reviewed')))
        with patch.object(runner, 'api', return_value={'head': {'sha': 'a' * 40}, 'number': 1}), \
             patch.object(runner, 'emit') as emit, patch.object(runner, 'output') as output:
            runner.qa_publish()
        self.assertEqual(emit.call_args_list[0].args[1], 'fail')
        output.assert_called_once_with('qa_status', 'fail')

if __name__ == '__main__':
    unittest.main()
