import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('release_control', ROOT / '.github/scripts/release_control.py')
CONTROL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTROL)
# One Maven invocation per shipping artifact. Read from the manifest so a roster change is a
# one-file edit rather than a count to chase through these fixtures.
# The independent check that this roster matches the BOM and STABILITY.md lives in
# test_workflow_contract.py's test_publication_roster_matches_bom_and_root_version.
ARTIFACTS = json.loads((ROOT / '.github/release-manifest.json').read_text())['artifacts']


class ReleaseSimulation(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / '.github/scripts').mkdir(parents=True)
        shutil.copy(ROOT / '.github/scripts/release_control.py', self.root / '.github/scripts')
        shutil.copy(ROOT / '.github/release-manifest.json', self.root / '.github')
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha01\n')
        (self.root / 'CHANGELOG.md').write_text('## [6.0.0-alpha01] (2026-09-06)\n\nRelease changes.\n')
        binaries = self.root / 'bin'
        binaries.mkdir()
        self.env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                        GITHUB_REPOSITORY='MobileNativeFoundation/Store', GITHUB_EVENT_NAME='push',
                        GITHUB_REF='refs/tags/v6.0.0-alpha01', GITHUB_SHA='a' * 40,
                        GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1', STUB_DIRECTORY=str(self.root))
        needs = {name: {'result': 'success', 'outputs': dict(source_sha='a' * 40, run_id='123',
                                                          run_attempt='1', version='6.0.0-alpha01')} for name in
                 json.loads((ROOT / '.github/release-manifest.json').read_text())['release_jobs']}
        for name in ['release-matrix', 'release-full-suite']:
            needs[name]['outputs'] = dict(source_sha='a' * 40, run_id='123', run_attempt='1', version='6.0.0-alpha01')
        self.env['VALIDATION_NEEDS'] = json.dumps(needs)
        self.executable(binaries / 'git', 'import os\nprint(os.environ["GITHUB_SHA"])\n')
        self.executable(binaries / 'gh', '''import json, os, sys
from pathlib import Path
root = Path(os.environ['STUB_DIRECTORY'])
state = root / 'github-release.json'
operation = sys.argv[2]
if operation == 'view':
    if not state.exists():
        sys.exit(1)
    print(state.read_text())
elif operation == 'create':
    if state.exists():
        sys.exit(1)
    state.write_text(json.dumps(dict(tagName=sys.argv[3], draft=True)))
elif operation == 'upload':
    if os.environ.get('STUB_FAIL_RECORD') == '1':
        sys.exit(1)
    receipt = Path(sys.argv[4])
    (root / 'uploaded-receipt.json').write_text(receipt.read_text())
elif operation == 'edit':
    record = json.loads(state.read_text())
    record['draft'] = False
    state.write_text(json.dumps(record))
else:
    sys.exit(2)
''')
        self.executable(self.root / 'gradlew', '''import os, sys
from pathlib import Path
root = Path(os.environ['STUB_DIRECTORY'])
with (root / 'maven-calls.txt').open('a') as calls:
    calls.write(sys.argv[1] + '\\n')
if os.environ.get('STUB_FAIL_MODULE') and sys.argv[1].startswith(':' + os.environ['STUB_FAIL_MODULE'] + ':'):
    sys.exit(1)
''')

    def executable(self, path, body):
        path.write_text('#!/usr/bin/env python3\n' + body)
        path.chmod(0o755)

    def run_command(self, command, success=True, arguments=()):
        result = subprocess.run(['python3', '.github/scripts/release_control.py', command, *arguments],
                                cwd=self.root, env=self.env, text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def test_complete_publish_then_record_failure_and_idempotent_repair(self):
        for command in ['gate', 'reserve', 'publish']:
            self.run_command(command)
        receipt = (self.root / 'publication-receipt.json').read_bytes()
        self.assertEqual(len((self.root / 'maven-calls.txt').read_text().splitlines()), len(ARTIFACTS))
        self.env['STUB_FAIL_RECORD'] = '1'
        self.run_command('record', success=False)
        self.assertEqual((self.root / 'publication-receipt.json').read_bytes(), receipt)
        self.run_command('reserve', success=False)
        self.env.pop('STUB_FAIL_RECORD')
        self.run_command('record')
        self.run_command('record')
        self.assertEqual((self.root / 'uploaded-receipt.json').read_bytes(), receipt)
        self.assertFalse(json.loads((self.root / 'github-release.json').read_text())['draft'])
        self.assertEqual(len((self.root / 'maven-calls.txt').read_text().splitlines()), len(ARTIFACTS))

    def test_partial_maven_success_blocks_release_record_and_automatic_retry(self):
        for command in ['gate', 'reserve']:
            self.run_command(command)
        self.env['STUB_FAIL_MODULE'] = 'testing'
        self.run_command('publish', success=False)
        receipt = json.loads((self.root / 'publication-receipt.json').read_text())
        self.assertEqual(receipt['published_modules'], ['core'])
        self.assertEqual(receipt['attempting_module'], 'testing')
        self.run_command('record', success=False)
        self.run_command('reserve', success=False)
        self.assertEqual(len((self.root / 'maven-calls.txt').read_text().splitlines()), 2)

    def test_snapshot_dispatch_uses_snapshot_tasks_without_github_release(self):
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-SNAPSHOT\n')
        self.env.update(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/store6')
        needs = json.loads(self.env['VALIDATION_NEEDS'])
        for job in needs.values():
            job['outputs']['version'] = '6.0.0-SNAPSHOT'
        self.env['VALIDATION_NEEDS'] = json.dumps(needs)
        for command in ['gate', 'reserve', 'publish', 'record']:
            self.run_command(command)
        self.assertFalse((self.root / 'github-release.json').exists())
        calls = (self.root / 'maven-calls.txt').read_text().splitlines()
        self.assertEqual(len(calls), len(ARTIFACTS))
        self.assertTrue(all(call.endswith(':publishToMavenCentral') for call in calls))

    def write_execution(self, name, **fields):
        record = dict(schema_version=1, source_sha='a' * 40, checked_out_sha='a' * 40,
                      version='6.0.0-alpha01', repository='MobileNativeFoundation/Store',
                      run_id='123', run_attempt='1', gradle_exit_code=0, classification='passed',
                      task_outcome='executed', log_sha256='0' * 64)
        record.update(fields)
        path = self.root / 'full-suite-artifacts' / name / 'full-suite-evidence' / 'execution.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(record))

    def write_full_suite_executions(self, shards=4):
        lincheck = 'org.mobilenativefoundation.store6.mutations.MutationJournalLincheckTest'
        for package, name in [('example', 'ExampleTest'), (lincheck.rsplit('.', 1)[0], lincheck.rsplit('.', 1)[1])]:
            source = self.root / 'mutations/src/jvmTest/kotlin' / (name + '.kt')
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('package ' + package + '\nclass ' + name + '\n')
        self.write_execution('results-jvmTest', task=':mutations:jvmTest', shard=None,
                             executed_classes=['example.ExampleTest'],
                             test_identifiers=[dict(id='example.ExampleTest#works', outcome='passed')])
        for index in range(1, shards + 1):
            indices = [value for value in range(CONTROL.LINCHECK_SCENARIO_COUNT)
                       if value % shards == index - 1]
            self.write_execution(
                'results-lincheck-' + str(index), task=':mutations:lincheckTest',
                shard=f'{index}/{shards}',
                scenario_indices=indices,
                scenario_digest=CONTROL.LINCHECK_SCENARIO_DIGEST,
                executed_iterations=len(indices),
                executed_classes=[lincheck],
                test_identifiers=[dict(id=lincheck + '#inMemoryJournalTransactions_areLinearizable',
                                       outcome='passed')])
        self.env['FULL_SUITE_EXECUTIONS'] = 'full-suite-artifacts'
        self.env['FULL_SUITE_SHARDS'] = str(shards)

    def test_matrix_and_full_suite_cli_preserve_leaf_attempt_provenance(self):
        manifest = json.loads((self.root / '.github/release-manifest.json').read_text())
        output = self.root / 'job-outputs.txt'
        self.env['GITHUB_OUTPUT'] = str(output)
        self.write_full_suite_executions()
        for command, required in [('matrix', manifest['matrix_jobs']),
                                  ('full-suite', manifest['full_suite_jobs'])]:
            with self.subTest(command=command):
                needs = {name: dict(result='success', outputs=dict(source_sha='a' * 40, run_id='123',
                                                                  run_attempt='1', version='6.0.0-alpha01'))
                         for name in required}
                self.env['VALIDATION_NEEDS'] = json.dumps(needs)
                self.run_command(command)
                record = json.loads((self.root / 'release-evidence.json').read_text())
                self.assertEqual(record['checks'], needs)
                self.assertIn('version=6.0.0-alpha01\n', output.read_text())
                saved_outputs = output.read_bytes()
                for name in required:
                    with self.subTest(job=name):
                        needs[name]['outputs']['run_attempt'] = '0'
                        self.env['VALIDATION_NEEDS'] = json.dumps(needs)
                        self.run_command(command, success=False)
                        self.assertEqual(output.read_bytes(), saved_outputs)
                        needs[name]['outputs']['run_attempt'] = '1'
        self.assertFalse((self.root / 'maven-calls.txt').exists())

    def test_the_full_suite_cli_refuses_an_incomplete_shard_census(self):
        manifest = json.loads((self.root / '.github/release-manifest.json').read_text())
        output = self.root / 'job-outputs.txt'
        self.env['GITHUB_OUTPUT'] = str(output)
        self.write_full_suite_executions()
        self.env['VALIDATION_NEEDS'] = json.dumps({
            name: dict(result='success', outputs=dict(source_sha='a' * 40, run_id='123',
                                                      run_attempt='1', version='6.0.0-alpha01'))
            for name in manifest['full_suite_jobs']})
        record = self.root / 'full-suite-artifacts/results-lincheck-2/full-suite-evidence/execution.json'
        saved = record.read_text()
        record.unlink()
        self.run_command('full-suite', success=False)
        self.assertFalse(output.exists())
        record.write_text(saved)
        for missing in ['FULL_SUITE_EXECUTIONS', 'FULL_SUITE_SHARDS']:
            with self.subTest(missing=missing):
                value = self.env.pop(missing)
                self.run_command('full-suite', success=False)
                self.assertFalse(output.exists())
                self.env[missing] = value
        self.run_command('full-suite')
        self.assertIn('version=6.0.0-alpha01\n', output.read_text())


if __name__ == '__main__':
    unittest.main()
