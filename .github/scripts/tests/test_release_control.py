import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / '.github/scripts/release_control.py'
SPEC = importlib.util.spec_from_file_location('release_control', SCRIPT) if SCRIPT.exists() else None
CONTROL = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    SPEC.loader.exec_module(CONTROL)


class ReleaseFixtures(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(CONTROL, 'The fail-closed release controller does not exist')
        self.sha = 'a' * 40
        self.context = dict(repository='MobileNativeFoundation/Store', event='push',
                            ref='refs/tags/v6.0.0-alpha01', sha=self.sha,
                            checked_out_sha=self.sha, run_id='123', run_attempt='1')
        self.manifest = json.loads((ROOT / '.github/release-manifest.json').read_text())
        self.needs = {name: {'result': 'success', 'outputs': {
            'source_sha': self.sha, 'run_id': '123', 'run_attempt': '1', 'version': '6.0.0-alpha01'}}
                      for name in self.manifest['release_jobs']}
        for name in ['release-matrix', 'release-full-suite']:
            self.needs[name]['outputs'] = {'source_sha': self.sha, 'run_id': '123',
                                          'run_attempt': '1', 'version': '6.0.0-alpha01'}

    def gate(self, version='6.0.0-alpha01'):
        return CONTROL.release_evidence(self.context, version, self.manifest, self.needs)

    def test_valid_tag(self):
        record = self.gate()
        self.assertEqual(record['source_sha'], self.sha)
        self.assertEqual(record['artifacts'], self.manifest['artifacts'])
        self.assertEqual(record['classification'], 'validated')

    def test_mismatched_version(self):
        with self.assertRaisesRegex(ValueError, 'version'):
            self.gate('6.0.0-alpha02')

    def test_snapshot_tag(self):
        self.context['ref'] = 'refs/tags/v6.0.0-SNAPSHOT'
        with self.assertRaisesRegex(ValueError, 'SNAPSHOT'):
            self.gate('6.0.0-SNAPSHOT')

    def test_forbidden_repository(self):
        self.context['repository'] = 'someone/Store'
        with self.assertRaisesRegex(ValueError, 'repository'):
            self.gate()

    def test_missing_matrix(self):
        del self.needs['release-matrix']
        with self.assertRaisesRegex(ValueError, 'missing'):
            self.gate()

    def test_failed_pending_cancelled_skipped_matrix(self):
        for result in ['failure', 'pending', 'cancelled', 'skipped', '']:
            with self.subTest(result=result):
                self.needs['release-matrix']['result'] = result
                with self.assertRaisesRegex(ValueError, 'success'):
                    self.gate()

    def test_wrong_sha_green(self):
        self.needs['release-matrix']['outputs']['source_sha'] = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'SHA'):
            self.gate()

    def test_wrong_run_or_attempt(self):
        for key in ['run_id', 'run_attempt']:
            with self.subTest(key=key):
                changed = copy.deepcopy(self.needs)
                changed['release-full-suite']['outputs'][key] = '99'
                with self.assertRaisesRegex(ValueError, 'provenance'):
                    CONTROL.release_evidence(self.context, '6.0.0-alpha01', self.manifest, changed)

    def test_moved_checkout(self):
        self.context['checked_out_sha'] = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'SHA'):
            self.gate()

    def test_snapshot_dispatch(self):
        self.context.update(event='workflow_dispatch', ref='refs/heads/store6')
        for job in self.needs.values():
            job['outputs']['version'] = '6.0.0-SNAPSHOT'
        self.assertEqual(self.gate('6.0.0-SNAPSHOT')['version'], '6.0.0-SNAPSHOT')

    def test_release_dispatch_denied(self):
        self.context.update(event='workflow_dispatch', ref='refs/heads/store6')
        with self.assertRaisesRegex(ValueError, 'snapshots only'):
            self.gate()

    def test_untrusted_event_denied(self):
        self.context['event'] = 'pull_request'
        with self.assertRaises(ValueError):
            self.gate()

    def test_complete_matrix_required(self):
        needs = {name: {'result': 'success'} for name in self.manifest['matrix_jobs']}
        CONTROL.validate_jobs(needs, self.manifest['matrix_jobs'])
        for name in self.manifest['matrix_jobs']:
            with self.subTest(name=name):
                partial = copy.deepcopy(needs)
                del partial[name]
                with self.assertRaises(ValueError):
                    CONTROL.validate_jobs(partial, self.manifest['matrix_jobs'])

    def test_release_notes_required_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / 'CHANGELOG.md'
            notes.write_text('## [6.0.0-alpha01] (2026-09-06)\n\nConcrete changes.\n\n## [5.0.0]\nOlder.\n')
            self.assertIn('Concrete changes.', CONTROL.release_notes(notes, '6.0.0-alpha01'))
            with self.assertRaises(ValueError):
                CONTROL.release_notes(notes, '6.0.0-alpha02')
            notes.write_text('## [6.0.0-alpha01]\n\nTODO\n')
            with self.assertRaises(ValueError):
                CONTROL.release_notes(notes, '6.0.0-alpha01')

    def test_publish_receipt_survives_record_failure_and_repairs_without_publish(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'publication-receipt.json'
            record = self.gate()
            CONTROL.publish_modules(record, path, lambda task: calls.append(task))
            saved = json.loads(path.read_text())
            self.assertEqual(saved['publication_status'], 'complete')
            with self.assertRaisesRegex(RuntimeError, 'record unavailable'):
                CONTROL.repair_record(saved, lambda _: (_ for _ in ()).throw(RuntimeError('record unavailable')))
            self.assertEqual(json.loads(path.read_text()), saved)
            updates = []
            CONTROL.repair_record(saved, lambda receipt: updates.append(receipt))
            CONTROL.repair_record(saved, lambda receipt: updates.append(receipt))
            self.assertEqual(len(calls), len(self.manifest['artifacts']))
            self.assertEqual(updates[0], updates[1])

    def test_partial_publication_is_not_republished(self):
        calls = []
        def publish(task):
            calls.append(task)
            if len(calls) == 2:
                raise RuntimeError('Central unavailable')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'receipt.json'
            with self.assertRaises(RuntimeError):
                CONTROL.publish_modules(self.gate(), path, publish)
            receipt = json.loads(path.read_text())
            self.assertEqual(receipt['publication_status'], 'partial')
            self.assertEqual(receipt['published_modules'], ['core'])
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                CONTROL.repair_record(receipt, lambda _: self.fail('No release for partial Maven deployment'))
            with self.assertRaisesRegex(ValueError, 'receipt already exists'):
                CONTROL.publish_modules(self.gate(), path, publish)
            self.assertEqual(len(calls), 2)

    def test_repeated_immutable_release_is_blocked_by_existing_record(self):
        with self.assertRaisesRegex(ValueError, 'repair'):
            CONTROL.require_new_release({'tag_name': self.context['ref'].removeprefix('refs/tags/')})
        CONTROL.require_new_release(None)

    def test_root_version_is_unique(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha02\n')
            self.assertEqual(CONTROL.root_version(root), '6.0.0-alpha02')
            (root / 'core').mkdir()
            (root / 'core/gradle.properties').write_text('VERSION_NAME=6.0.0-SNAPSHOT\n')
            with self.assertRaisesRegex(ValueError, 'override'):
                CONTROL.root_version(root)

    def test_publication_metadata_versions_and_bom_roster(self):
        self.assertTrue(hasattr(CONTROL, 'publication_versions'), 'Publication metadata must use the root version')
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            for module in ['core', 'bom']:
                target = repository / module / '6.0.0-alpha01'
                target.mkdir(parents=True)
                dependencies = ('<dependencyManagement><dependencies><dependency><groupId>org.example</groupId>'
                                '<artifactId>core</artifactId><version>6.0.0-alpha01</version></dependency>'
                                '</dependencies></dependencyManagement>') if module == 'bom' else ''
                (target / f'{module}-6.0.0-alpha01.pom').write_text(
                    '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>org.example</groupId>'
                    f'<artifactId>{module}</artifactId><version>6.0.0-alpha01</version>{dependencies}</project>')
                (target / f'{module}-6.0.0-alpha01.module').write_text(json.dumps(dict(
                    component=dict(group='org.example', module=module, version='6.0.0-alpha01'), variants=[])))
            CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core', 'bom'], ['core', 'bom'],
                                         expected_publications=['core', 'bom'])
            pom = repository / 'bom/6.0.0-alpha01/bom-6.0.0-alpha01.pom'
            pom.write_text(pom.read_text().replace('<version>6.0.0-alpha01</version></dependency>',
                                                 '<version>6.0.0-SNAPSHOT</version></dependency>'))
            with self.assertRaisesRegex(ValueError, 'version'):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core', 'bom'], ['core', 'bom'],
                                             expected_publications=['core', 'bom'])

    def test_full_suite_rejects_cached_up_to_date_and_missing_task(self):
        for task in [':mutations:jvmTest', ':mutations:lincheckTest']:
            for outcome in [' FROM-CACHE', ' UP-TO-DATE', ' SKIPPED', ' NO-SOURCE']:
                with self.subTest(task=task, outcome=outcome):
                    with self.assertRaisesRegex(ValueError, 'executed'):
                        CONTROL.task_outcome('> Task ' + task + outcome + '\n', task)
            with self.assertRaises(ValueError):
                CONTROL.task_outcome('BUILD SUCCESSFUL\n', task)
            self.assertEqual(CONTROL.task_outcome('> Task ' + task + '\n', task), 'executed')
            self.assertEqual(CONTROL.task_outcome('> Task ' + task + ' FAILED\n', task), 'failed')

    def test_each_lane_reads_only_its_own_task_outcome(self):
        both = '> Task :mutations:jvmTest\n> Task :mutations:lincheckTest FAILED\n'
        self.assertEqual(CONTROL.task_outcome(both, ':mutations:jvmTest'), 'executed')
        self.assertEqual(CONTROL.task_outcome(both, ':mutations:lincheckTest'), 'failed')
        with self.assertRaisesRegex(ValueError, 'executed'):
            CONTROL.task_outcome('> Task :mutations:jvmTest\n', ':mutations:lincheckTest')

    def test_full_suite_task_names_and_repeated_failed_outcome(self):
        neighboring_tasks = (
            '> Task :mutations:jvmTestProcessResources NO-SOURCE\n'
            '> Task :mutations:jvmTestClasses\n'
        )
        observed_failure = (
            neighboring_tasks + '> Task :mutations:jvmTest\n'
            '284 tests completed, 6 failed\n'
            '> Task :mutations:jvmTest FAILED\n'
            'BUILD FAILED in 7s\n'
        )
        task = ':mutations:jvmTest'
        self.assertEqual(CONTROL.task_outcome(observed_failure, task), 'failed')
        self.assertEqual(CONTROL.task_outcome(neighboring_tasks + '> Task :mutations:jvmTest\n', task), 'executed')
        self.assertEqual(CONTROL.task_outcome(neighboring_tasks + '> Task :mutations:jvmTest FAILED\n', task), 'failed')
        self.assertEqual(CONTROL.task_outcome('> Task :mutations:jvmTest\r\n', task), 'executed')
        self.assertEqual(CONTROL.task_outcome('> Task :mutations:jvmTest FAILED\n' * 2, task), 'failed')
        with self.assertRaisesRegex(ValueError, 'executed'):
            CONTROL.task_outcome(neighboring_tasks, task)
        for outcome in ['FROM-CACHE', 'UP-TO-DATE', 'SKIPPED', 'NO-SOURCE']:
            with self.subTest(outcome=outcome):
                for started in ['', '> Task :mutations:jvmTest\n']:
                    with self.subTest(started=bool(started)):
                        with self.assertRaisesRegex(ValueError, 'executed'):
                            CONTROL.task_outcome(
                                neighboring_tasks + started + '> Task :mutations:jvmTest ' + outcome + '\n', task)

    def test_shard_indices_partition_the_whole_scenario_plan(self):
        self.assertEqual(CONTROL.LINCHECK_SCENARIO_COUNT, 101)
        for count in range(1, 9):
            union = []
            for index in range(1, count + 1):
                union += CONTROL.shard_indices(f'{index}/{count}')
            self.assertEqual(sorted(union), list(range(CONTROL.LINCHECK_SCENARIO_COUNT)))
        self.assertEqual(CONTROL.shard_indices('1/4')[:3], [0, 4, 8])
        self.assertEqual(CONTROL.shard_indices('1/4')[-1], CONTROL.LINCHECK_SCENARIO_COUNT - 1)
        for malformed in ['', '1', '0/4', '5/4', '1/0', '1/102', 'a/b', '1/4/2', None]:
            with self.subTest(shard=malformed):
                with self.assertRaisesRegex(ValueError, 'k/N'):
                    CONTROL.shard_indices(malformed)

    def test_full_suite_execution_requires_an_explicit_task(self):
        argv = ['release_control.py', 'full-suite-execution', '--log', 'gradle.log']
        with mock.patch.object(CONTROL.sys, 'argv', argv), contextlib.redirect_stderr(io.StringIO()) as err:
            with self.assertRaises(SystemExit) as raised:
                CONTROL.main()
        self.assertEqual(raised.exception.code, 2)
        self.assertIn('--task', err.getvalue())

    def test_full_suite_identifiers_and_skipped_class_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory)
            xml = results / 'TEST-example.Test.xml'
            xml.write_text('<testsuite><testcase classname="example.Test" name="works"/></testsuite>')
            record = CONTROL.test_identifiers(results, ['example.Test'])
            self.assertEqual(record[0]['id'], 'example.Test#works')
            xml.write_text('<testsuite><testcase classname="example.Test" name="works"><skipped/></testcase></testsuite>')
            with self.assertRaisesRegex(ValueError, 'executed'):
                CONTROL.test_identifiers(results, ['example.Test'])

    def test_every_release_job_requires_current_source_run_attempt_and_version(self):
        for name in self.manifest['release_jobs']:
            for key in ['source_sha', 'run_id', 'run_attempt', 'version']:
                for mismatch in [None, 'different']:
                    with self.subTest(job=name, field=key, mismatch=mismatch):
                        needs = copy.deepcopy(self.needs)
                        if mismatch is None:
                            needs[name]['outputs'].pop(key)
                        else:
                            needs[name]['outputs'][key] = mismatch
                        with self.assertRaises(ValueError):
                            CONTROL.release_evidence(self.context, '6.0.0-alpha01', self.manifest, needs)

    def test_matrix_and_full_suite_jobs_require_each_leaf_provenance(self):
        validate = getattr(CONTROL, 'validate_job_provenance', None)
        self.assertIsNotNone(validate, 'Required leaf jobs need individual provenance validation')
        for required in [self.manifest['matrix_jobs'], self.manifest['full_suite_jobs']]:
            needs = {name: copy.deepcopy(self.needs['build-and-test']) for name in required}
            validate(self.context, '6.0.0-alpha01', needs, required)
            for name in required:
                with self.subTest(job=name):
                    changed = copy.deepcopy(needs)
                    changed[name]['outputs']['run_attempt'] = '0'
                    with self.assertRaisesRegex(ValueError, 'provenance'):
                        validate(self.context, '6.0.0-alpha01', changed, required)

    def test_a_reattempted_shard_matrix_cannot_relabel_the_jvm_suite(self):
        validate = getattr(CONTROL, 'validate_job_provenance', None)
        self.assertIsNotNone(validate, 'The full-suite gate must compare every execution attempt')
        context = dict(self.context, run_attempt='2')
        required = self.manifest['full_suite_jobs']
        needs = {name: copy.deepcopy(self.needs['build-and-test']) for name in required}
        needs[required[-1]]['outputs']['run_attempt'] = '2'
        with self.assertRaisesRegex(ValueError, required[0] + '.*provenance'):
            validate(context, '6.0.0-alpha01', needs, required)

    def test_publication_metadata_requires_an_explicit_target_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_publication(repository, 'core')
            with self.assertRaisesRegex(ValueError, 'inventory'):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'])

    def test_expected_target_requires_its_own_pom_and_module_metadata(self):
        for extension in ['pom', 'module']:
            with self.subTest(extension=extension), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                self.write_publication(repository, 'core')
                target = self.write_publication(repository, 'core-jvm')
                (target / 'core-jvm-6.0.0-alpha01.jar').write_bytes(b'publication fixture')
                (target / f'core-jvm-6.0.0-alpha01.{extension}').unlink()
                with self.assertRaisesRegex(ValueError, 'missing.*core-jvm'):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=['core', 'core-jvm'])

    def test_complete_expected_publication_inventory_is_validated_once(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            for name in ['mutations', 'mutations-testing', 'mutations-jvm', 'mutations-testing-jvm']:
                self.write_publication(repository, name)
            expected = ['mutations', 'mutations-testing', 'mutations-jvm', 'mutations-testing-jvm']
            count = CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example',
                                                 ['mutations', 'mutations-testing'], ['mutations', 'mutations-testing'],
                                                 expected_publications=expected)
            self.assertEqual(count, len(expected))

    def test_unlisted_target_cannot_escape_publication_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_publication(repository, 'core')
            self.write_publication(repository, 'core-jvm')
            with self.assertRaisesRegex(ValueError, 'unexpected.*core-jvm'):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                             expected_publications=['core'])

    def test_generated_kmp_targets_link_to_the_owning_root_component(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            expected = self.write_kmp_publications(repository)
            count = CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example',
                                                 ['core'], ['core'], expected_publications=expected)
            self.assertEqual(count, 4)

    def test_kmp_component_owner_uses_the_longest_module_name(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            expected = self.write_kmp_publications(repository, 'mutations')
            expected += self.write_kmp_publications(repository, 'mutations-testing')
            modules = ['mutations', 'mutations-testing']
            self.assertEqual(CONTROL.publication_versions(
                repository, '6.0.0-alpha01', 'org.example', modules, modules,
                expected_publications=expected), 8)
            metadata = repository / 'mutations-testing-jvm/6.0.0-alpha01/mutations-testing-jvm-6.0.0-alpha01.module'
            data = json.loads(metadata.read_text())
            data['component'].update(module='mutations', url='../../mutations/6.0.0-alpha01/mutations-6.0.0-alpha01.module')
            metadata.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', modules, modules,
                                             expected_publications=expected)

    def test_kmp_target_component_rejects_wrong_coordinates(self):
        for field, value in [('module', 'unrelated'), ('group', 'org.foreign'), ('version', '6.0.0-SNAPSHOT')]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                expected = self.write_kmp_publications(repository)
                metadata = repository / 'core-jvm/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module'
                data = json.loads(metadata.read_text())
                data['component'][field] = value
                metadata.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=expected)

    def test_kmp_target_component_rejects_missing_or_forged_urls(self):
        urls = [None, '', 7, '../../core/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module',
                '../../other/6.0.0-alpha01/other-6.0.0-alpha01.module',
                '../../core/6.0.0-SNAPSHOT/core-6.0.0-SNAPSHOT.module',
                '../../../outside/core-6.0.0-alpha01.module',
                '../../%63ore/6.0.0-alpha01/core-6.0.0-alpha01.module',
                'https://example.invalid/core-6.0.0-alpha01.module',
                '/core/6.0.0-alpha01/core-6.0.0-alpha01.module',
                '../../core/6.0.0-alpha01/core-6.0.0-alpha01.module?forged=1']
        for url in urls:
            with self.subTest(url=url), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                expected = self.write_kmp_publications(repository)
                metadata = repository / 'core-jvm/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module'
                data = json.loads(metadata.read_text())
                if url is None:
                    data['component'].pop('url')
                else:
                    data['component']['url'] = url
                metadata.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=expected)

    def test_component_url_cannot_disguise_a_self_component(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            target = self.write_publication(repository, 'core')
            metadata = target / 'core-6.0.0-alpha01.module'
            data = json.loads(metadata.read_text())
            data['component']['url'] = '../../unrelated/6.0.0-alpha01/unrelated-6.0.0-alpha01.module'
            metadata.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                             expected_publications=['core'])

    def test_kmp_component_url_cannot_follow_a_symlink_outside_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory) / 'repository'
            expected = self.write_kmp_publications(repository)
            metadata = repository / 'core/6.0.0-alpha01/core-6.0.0-alpha01.module'
            outside = Path(directory) / 'outside.module'
            outside.write_bytes(metadata.read_bytes())
            metadata.unlink()
            metadata.symlink_to(outside)
            with self.assertRaises(ValueError):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                             expected_publications=expected)

    def test_kmp_link_preserves_root_and_target_pom_and_metadata_requirements(self):
        for artifact in ['core', 'core-jvm']:
            for extension in ['pom', 'module']:
                with self.subTest(artifact=artifact, extension=extension), tempfile.TemporaryDirectory() as directory:
                    repository = Path(directory)
                    expected = self.write_kmp_publications(repository)
                    (repository / artifact / '6.0.0-alpha01' / f'{artifact}-6.0.0-alpha01.{extension}').unlink()
                    with self.assertRaisesRegex(ValueError, 'missing'):
                        CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                     expected_publications=expected)

    def test_kmp_link_preserves_internal_metadata_version_checks(self):
        for field in ['dependencies', 'dependencyConstraints', 'available-at']:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                expected = self.write_kmp_publications(repository)
                metadata = repository / 'core-jvm/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module'
                data = json.loads(metadata.read_text())
                dependency = dict(group='org.example', module='core', version=dict(requires='6.0.0-SNAPSHOT'))
                data['variants'][0][field] = (dict(dependency, version='6.0.0-SNAPSHOT')
                                            if field == 'available-at' else [dependency])
                metadata.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, 'version'):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=expected)

    def write_kmp_publications(self, repository, owner='core'):
        version = '6.0.0-alpha01'
        expected = [owner] + [owner + suffix for suffix in ['-android', '-jvm', '-iosarm64']]
        root = self.write_publication(repository, owner)
        root_metadata = root / f'{owner}-{version}.module'
        root_data = dict(formatVersion='1.1', component=dict(
            group='org.example', module=owner, version=version, attributes={'org.gradle.status': 'release'}), variants=[])
        for artifact, extension in zip(expected[1:], ['aar', 'jar', 'klib']):
            target = self.write_publication(repository, artifact)
            metadata = target / f'{artifact}-{version}.module'
            filename = f'{artifact}-{version}.{extension}'
            (target / filename).write_bytes(b'publication fixture')
            target_data = dict(formatVersion='1.1', component=dict(
                url=f'../../{owner}/{version}/{owner}-{version}.module', group='org.example',
                module=owner, version=version, attributes={'org.gradle.status': 'release'}), variants=[dict(
                    name='apiElements-published', files=[dict(name=filename, url=filename)],
                    dependencies=[dict(group='org.example', module=owner, version=dict(requires=version))])])
            metadata.write_text(json.dumps(target_data))
            root_data['variants'].append({'name': artifact, 'available-at': dict(
                url=f'../../{artifact}/{version}/{artifact}-{version}.module',
                group='org.example', module=artifact, version=version)})
        root_metadata.write_text(json.dumps(root_data))
        return expected

    def write_publication(self, repository, artifact):
        target = repository / artifact / '6.0.0-alpha01'
        target.mkdir(parents=True)
        (target / f'{artifact}-6.0.0-alpha01.pom').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>org.example</groupId>'
            f'<artifactId>{artifact}</artifactId><version>6.0.0-alpha01</version></project>')
        (target / f'{artifact}-6.0.0-alpha01.module').write_text(json.dumps(dict(
            component=dict(group='org.example', module=artifact, version='6.0.0-alpha01'), variants=[])))
        return target



class FullSuiteInstrumentationFixtures(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha01\n')
        source = self.root / 'mutations/src/jvmTest/kotlin/ExampleTest.kt'
        source.parent.mkdir(parents=True)
        source.write_text('package example\nclass ExampleTest\n')
        self.results = self.root / 'results'
        self.results.mkdir()
        self.xml = self.results / 'TEST-example.ExampleTest.xml'
        self.log = self.root / 'gradle.log'
        self.output = self.root / 'execution.json'
        self.context = dict(sha='a' * 40, checked_out_sha='a' * 40,
                            repository='example/repository', run_id='123', run_attempt='1')
        root_patch = mock.patch.object(CONTROL, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        output_patch = mock.patch.object(CONTROL, 'append_outputs')
        self.append_outputs = output_patch.start()
        self.addCleanup(output_patch.stop)
        summary_patch = mock.patch.dict(CONTROL.os.environ, {'GITHUB_STEP_SUMMARY': ''})
        summary_patch.start()
        self.addCleanup(summary_patch.stop)
        self.write_inputs()

    def write_inputs(self, log_extra='', stderr='', failure=False, name='works', cdata=False):
        suite = ET.Element('testsuite', tests='1', failures='1' if failure else '0', errors='0', skipped='0')
        testcase = ET.SubElement(suite, 'testcase', classname='example.ExampleTest', name=name)
        if failure:
            ET.SubElement(testcase, 'failure', message='original assertion').text = 'original assertion'
        error_stream = ET.SubElement(suite, 'system-err')
        error_stream.text = stderr
        xml = ET.tostring(suite, encoding='unicode')
        if cdata:
            xml = xml.replace('<system-err>' + stderr + '</system-err>',
                              '<system-err><![CDATA[' + stderr + ']]></system-err>')
        self.xml.write_text(xml)
        self.log.write_text('> Task :mutations:jvmTest' + (' FAILED' if failure else '') + '\n' + log_extra)

    def record(self, exit_code=0, task='jvmTest', shard=None):
        args = SimpleNamespace(output=str(self.output), log=str(self.log), results=str(self.results),
                               task=task, shard=shard, exit_code=exit_code)
        CONTROL.full_suite_record(args, self.context, {})

    def saved(self):
        record = json.loads(self.output.read_text())
        self.assertEqual(record['expected_classes'], ['example.ExampleTest'])
        self.assertEqual(len(record['test_identifiers']), 1)
        self.assertIn(self.xml.name, record['xml_files'])
        return record

    def test_green_xml_and_successful_task_reject_log_transform_failure(self):
        self.write_inputs(log_extra='  Unable to transform org/example/Record$Nested\n')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record()
        record = self.saved()
        self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
        self.assertEqual(record['test_identifiers'][0]['outcome'], 'passed')
        self.assertEqual(record['instrumentation_failures'][0]['class_name'], 'org/example/Record$Nested')
        self.assertEqual(record['instrumentation_failures'][0]['source'], 'gradle-log')
        self.assertEqual(record['instrumentation_failures'][0]['line'], 2)
        self.append_outputs.assert_not_called()

    def test_green_xml_rejects_decoded_stderr_and_cdata_transform_failures(self):
        for cdata in [False, True]:
            with self.subTest(cdata=cdata):
                self.write_inputs(stderr='\n\tUnable to transform org/example/Record$Nested\n', cdata=cdata)
                if not cdata:
                    self.xml.write_text(self.xml.read_text().replace('transform', 'trans&#102;orm'))
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record()
                record = self.saved()
                self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
                marker = record['instrumentation_failures'][0]
                self.assertEqual(marker['class_name'], 'org/example/Record$Nested')
                self.assertEqual(marker['source'], 'xml-system-err')
                self.assertEqual(marker['path'], self.xml.name)
                self.assertEqual(marker['line'], 2)
                self.append_outputs.assert_not_called()

    def test_real_test_failure_keeps_classification_and_testcase_with_marker(self):
        self.write_inputs(stderr='Unable to transform org/example/Record\n', failure=True)
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(exit_code=1)
        record = self.saved()
        self.assertEqual(record['classification'], 'test-failure')
        self.assertEqual(record['test_identifiers'], [{'id': 'example.ExampleTest#works', 'outcome': 'failed'}])
        self.assertIn('instrumentation_failures', record)
        self.assertEqual(record['instrumentation_failures'][0]['class_name'], 'org/example/Record')
        self.assertEqual(ET.parse(self.xml).find('testcase/failure').get('message'), 'original assertion')
        self.append_outputs.assert_not_called()

    def test_log_marker_preserves_legal_jvm_class_name_characters(self):
        names = ["org/example/Foo'Bar", 'org/example/Foo"Bar', 'org/example/Foo`Bar',
                 'org/example/Foo Bar', '"org/example/QuotedClass"',
                 'org/example/Name extra words', ' Leading', 'Trailing ', ' \t ']
        for class_name in names:
            with self.subTest(class_name=class_name):
                self.write_inputs(log_extra='Unable to transform ' + class_name + '\n')
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record()
                record = self.saved()
                self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
                self.assertEqual(record['instrumentation_failures'][0]['class_name'], class_name)
                self.append_outputs.assert_not_called()

    def test_cdata_stderr_marker_preserves_legal_jvm_class_name_characters(self):
        names = ["org/example/Foo'Bar", 'org/example/Foo"Bar', 'org/example/Foo`Bar',
                 'org/example/Foo Bar', '"org/example/QuotedClass"',
                 'org/example/Name extra words', ' Leading', 'Trailing ', ' \t ']
        for class_name in names:
            with self.subTest(class_name=class_name):
                self.write_inputs(stderr='Unable to transform ' + class_name + '\n', cdata=True)
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record()
                record = self.saved()
                self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
                self.assertEqual(record['instrumentation_failures'][0]['class_name'], class_name)
                self.append_outputs.assert_not_called()

    def test_log_marker_records_actual_supplied_log_path(self):
        self.log = self.root / 'captured-worker-output.log'
        self.write_inputs(log_extra='Unable to transform org/example/Record\n')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record()
        self.assertEqual(self.saved()['instrumentation_failures'][0]['path'], str(self.log))
        self.append_outputs.assert_not_called()

    def test_clean_passing_execution_still_emits_provenance(self):
        self.record()
        self.assertEqual(self.saved()['classification'], 'passed')
        self.append_outputs.assert_called_once_with(self.context, '6.0.0-alpha01')

    def test_mentions_quotes_and_test_names_do_not_imply_transform_failure(self):
        mentions = (
            'ExampleTest > Unable to transform org/example/TestName PASSED\n'
            'An example says Unable to transform org/example/Prose\n'
            '"Unable to transform org/example/Quoted"\n'
            "'Unable to transform org/example/Quoted'\n"
        )
        self.write_inputs(log_extra=mentions, stderr=mentions,
                          name='Unable to transform org/example/TestName')
        self.record()
        self.assertEqual(self.saved()['classification'], 'passed')
        self.append_outputs.assert_called_once_with(self.context, '6.0.0-alpha01')

    def test_the_jvm_lane_refuses_results_carrying_the_lincheck_class(self):
        trespass = self.results / ('TEST-' + CONTROL.LINCHECK_CLASS + '.xml')
        trespass.write_text('<testsuite><testcase classname="' + CONTROL.LINCHECK_CLASS +
                            '" name="inMemoryJournalTransactions_areLinearizable"/></testsuite>')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record()
        record = json.loads(self.output.read_text())
        self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
        self.assertIn(CONTROL.LINCHECK_CLASS, record['evidence_error'])
        self.append_outputs.assert_not_called()

    def test_the_jvm_lane_expects_every_non_lincheck_class(self):
        source = self.root / 'mutations/src/commonTest/kotlin/OtherTest.kt'
        source.parent.mkdir(parents=True)
        source.write_text('package example\nclass OtherTest\n')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record()
        record = json.loads(self.output.read_text())
        self.assertIn('example.OtherTest', record['expected_classes'])
        self.assertIn('example.OtherTest', record['evidence_error'])


class LincheckShardExecutionFixtures(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(CONTROL, 'The fail-closed release controller does not exist')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha01\n')
        source = self.root / ('mutations/src/jvmTest/kotlin/' + CONTROL.LINCHECK_CLASS.rsplit('.', 1)[1] + '.kt')
        source.parent.mkdir(parents=True)
        source.write_text('package ' + CONTROL.LINCHECK_CLASS.rsplit('.', 1)[0] + '\nclass X\n')
        self.results = self.root / 'results'
        self.results.mkdir()
        self.log = self.root / 'gradle.log'
        self.output = self.root / 'execution.json'
        self.context = dict(sha='a' * 40, checked_out_sha='a' * 40,
                            repository='example/repository', run_id='123', run_attempt='1')
        root_patch = mock.patch.object(CONTROL, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        output_patch = mock.patch.object(CONTROL, 'append_outputs')
        self.append_outputs = output_patch.start()
        self.addCleanup(output_patch.stop)
        summary_patch = mock.patch.dict(CONTROL.os.environ, {'GITHUB_STEP_SUMMARY': ''})
        summary_patch.start()
        self.addCleanup(summary_patch.stop)

    def marker(self, shard='1/4', indices=None, digest=None):
        indices = CONTROL.shard_indices(shard) if indices is None else indices
        return (CONTROL.SCENARIO_MARKER + ' shard=' + shard + ' count=' + str(len(indices)) +
                ' indices=' + ','.join(str(value) for value in indices) +
                ' digest=' + (digest or CONTROL.LINCHECK_SCENARIO_DIGEST))

    def iterations(self, planned, executed=None):
        """What Lincheck's own reporter prints per scenario at LoggingLevel.INFO."""
        executed = planned if executed is None else executed
        return ''.join('= Iteration %d / %d =\n' % (index, planned)
                       for index in range(1, executed + 1))

    def write_inputs(self, marker=None, in_log=True, failure=False, iterations=None):
        suite = ET.Element('testsuite', tests='1', failures='1' if failure else '0',
                           errors='0', skipped='0')
        testcase = ET.SubElement(suite, 'testcase', classname=CONTROL.LINCHECK_CLASS,
                                 name='inMemoryJournalTransactions_areLinearizable')
        if failure:
            ET.SubElement(testcase, 'failure', message='not linearizable').text = 'not linearizable'
        if marker is not None and iterations is None:
            iterations = self.iterations(int(marker.split(' count=')[1].split(' ')[0]))
        standard_output = '' if marker is None else marker + '\n' + (iterations or '')
        ET.SubElement(suite, 'system-out').text = '' if in_log else standard_output
        (self.results / ('TEST-' + CONTROL.LINCHECK_CLASS + '.xml')).write_text(
            ET.tostring(suite, encoding='unicode'))
        log = '> Task :mutations:lincheckTest' + (' FAILED' if failure else '') + '\n'
        if in_log:
            log += ''.join('    ' + line + '\n' for line in standard_output.splitlines())
        self.log.write_text(log)

    def record(self, shard='1/4', exit_code=0):
        args = SimpleNamespace(output=str(self.output), log=str(self.log), results=str(self.results),
                               task='lincheckTest', shard=shard, exit_code=exit_code)
        CONTROL.full_suite_record(args, self.context, {})

    def test_a_clean_shard_records_its_scenario_indices_from_the_gradle_log(self):
        self.write_inputs(marker=self.marker('2/4'))
        self.record(shard='2/4')
        record = json.loads(self.output.read_text())
        self.assertEqual(record['classification'], 'passed')
        self.assertEqual(record['task'], ':mutations:lincheckTest')
        self.assertEqual(record['shard'], '2/4')
        self.assertEqual(record['scenario_indices'], CONTROL.shard_indices('2/4'))
        self.assertEqual(record['scenario_digest'], CONTROL.LINCHECK_SCENARIO_DIGEST)
        self.assertEqual(record['executed_iterations'], len(CONTROL.shard_indices('2/4')))
        self.assertEqual(record['executed_classes'], [CONTROL.LINCHECK_CLASS])
        self.append_outputs.assert_called_once_with(self.context, '6.0.0-alpha01')

    def test_standard_output_captured_only_in_the_result_xml_still_counts(self):
        self.write_inputs(marker=self.marker('3/4'), in_log=False)
        self.record(shard='3/4')
        self.assertEqual(json.loads(self.output.read_text())['scenario_indices'],
                         CONTROL.shard_indices('3/4'))

    def test_a_shard_without_scenario_evidence_is_refused(self):
        self.write_inputs(marker=None)
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record()
        self.assertIn(CONTROL.SCENARIO_MARKER, json.loads(self.output.read_text())['evidence_error'])
        self.append_outputs.assert_not_called()

    def test_indices_that_disagree_with_the_requested_shard_are_refused(self):
        for marker in [self.marker('1/4', CONTROL.shard_indices('1/4')[:-1]),
                       self.marker('1/4', CONTROL.shard_indices('2/4')),
                       self.marker('2/4')]:
            with self.subTest(marker=marker[:60]):
                self.write_inputs(marker=marker)
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record(shard='1/4')
                self.append_outputs.assert_not_called()

    def test_conflicting_scenario_markers_are_refused(self):
        self.write_inputs(marker=self.marker('1/4'))
        self.log.write_text(self.log.read_text() + '    ' + self.marker('2/4') + '\n')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4')
        self.append_outputs.assert_not_called()

    def test_a_failing_shard_keeps_its_test_failure_classification(self):
        self.write_inputs(marker=self.marker('1/4'), failure=True)
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4', exit_code=1)
        self.assertEqual(json.loads(self.output.read_text())['classification'], 'test-failure')
        self.append_outputs.assert_not_called()

    def test_a_shard_reporting_a_foreign_plan_digest_is_refused(self):
        self.write_inputs(marker=self.marker('1/4', digest='f' * 16))
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4')
        error = json.loads(self.output.read_text())['evidence_error']
        self.assertIn(CONTROL.LINCHECK_SCENARIO_DIGEST, error)
        self.append_outputs.assert_not_called()

    def test_a_shard_that_logged_fewer_iterations_than_it_planned_is_refused(self):
        planned = len(CONTROL.shard_indices('1/4'))
        self.write_inputs(marker=self.marker('1/4'),
                          iterations=self.iterations(planned, executed=planned - 1))
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4')
        record = json.loads(self.output.read_text())
        self.assertEqual(record['executed_iterations'], planned - 1)
        self.assertIn('iteration', record['evidence_error'])
        self.append_outputs.assert_not_called()

    def test_a_shard_with_no_iteration_evidence_at_all_is_refused(self):
        self.write_inputs(marker=self.marker('1/4'), iterations='')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4')
        self.assertIn('iteration', json.loads(self.output.read_text())['evidence_error'])

    def test_a_shard_whose_iteration_total_is_not_its_plan_is_refused(self):
        planned = len(CONTROL.shard_indices('1/4'))
        self.write_inputs(marker=self.marker('1/4'), iterations=self.iterations(planned + 100))
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4')
        self.assertIn('iteration', json.loads(self.output.read_text())['evidence_error'])

    def test_a_failing_shard_reports_its_short_iteration_count_without_masking_the_failure(self):
        planned = len(CONTROL.shard_indices('1/4'))
        self.write_inputs(marker=self.marker('1/4'), failure=True,
                          iterations=self.iterations(planned, executed=3))
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4', exit_code=1)
        record = json.loads(self.output.read_text())
        self.assertEqual(record['classification'], 'test-failure')
        self.assertEqual(record['executed_iterations'], 3)

    def test_the_lincheck_lane_refuses_an_empty_shard(self):
        for shard in [None, '', '   ']:
            with self.subTest(shard=shard):
                self.write_inputs(marker=self.marker('1/4'))
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record(shard=shard)
                self.assertIn('shard', json.loads(self.output.read_text())['evidence_error'])
                self.append_outputs.assert_not_called()

    def test_a_cached_shard_is_refused(self):
        self.write_inputs(marker=self.marker('1/4'))
        self.log.write_text('> Task :mutations:lincheckTest FROM-CACHE\n    ' + self.marker('1/4') + '\n' +
                            self.iterations(len(CONTROL.shard_indices('1/4'))))
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(shard='1/4')
        record = json.loads(self.output.read_text())
        self.assertEqual(record['classification'], 'unexecuted')
        self.append_outputs.assert_not_called()


class FullSuiteShardCensusFixtures(unittest.TestCase):
    SHARDS = 4

    def setUp(self):
        self.assertIsNotNone(CONTROL, 'The fail-closed release controller does not exist')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha01\n')
        for package, name in [('example', 'ExampleTest'),
                              (CONTROL.LINCHECK_CLASS.rsplit('.', 1)[0],
                               CONTROL.LINCHECK_CLASS.rsplit('.', 1)[1])]:
            source = self.root / ('mutations/src/jvmTest/kotlin/' + name + '.kt')
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('package ' + package + '\nclass ' + name + '\n')
        self.executions = self.root / 'artifacts'
        self.sha = 'a' * 40
        self.context = dict(repository='MobileNativeFoundation/Store', event='push',
                            ref='refs/tags/v6.0.0-alpha01', sha=self.sha, checked_out_sha=self.sha,
                            run_id='123', run_attempt='1')
        self.manifest = dict(full_suite_jobs=['full-mutations-jvm', 'lincheck'])
        self.needs = {name: dict(result='success', outputs=dict(
            source_sha=self.sha, run_id='123', run_attempt='1', version='6.0.0-alpha01'))
                      for name in self.manifest['full_suite_jobs']}
        root_patch = mock.patch.object(CONTROL, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.write_jvm()
        for index in range(1, self.SHARDS + 1):
            self.write_shard(index)

    def write_execution(self, name, **fields):
        record = dict(schema_version=1, source_sha=self.sha, checked_out_sha=self.sha,
                      version='6.0.0-alpha01', repository=self.context['repository'],
                      run_id='123', run_attempt='1', gradle_exit_code=0, classification='passed',
                      task_outcome='executed', log_sha256='0' * 64)
        record.update(fields)
        path = self.executions / name / 'full-suite-evidence' / 'execution.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(record))
        return path

    def write_jvm(self, **fields):
        fields.setdefault('executed_classes', ['example.ExampleTest'])
        fields.setdefault('test_identifiers', [dict(id='example.ExampleTest#works', outcome='passed')])
        return self.write_execution('full-jvm-results-jvmTest', task=':mutations:jvmTest', shard=None, **fields)

    def write_shard(self, index, **fields):
        shard = f'{index}/{self.SHARDS}'
        fields.setdefault('scenario_indices', CONTROL.shard_indices(shard))
        fields.setdefault('scenario_digest', CONTROL.LINCHECK_SCENARIO_DIGEST)
        fields.setdefault('executed_iterations', len(CONTROL.shard_indices(shard)))
        fields.setdefault('executed_classes', [CONTROL.LINCHECK_CLASS])
        fields.setdefault('test_identifiers', [
            dict(id=CONTROL.LINCHECK_CLASS + '#inMemoryJournalTransactions_areLinearizable', outcome='passed')])
        return self.write_execution('full-jvm-results-lincheck-' + str(index),
                                    task=':mutations:lincheckTest', shard=shard, **fields)

    def validate(self):
        return CONTROL.full_suite_validation(self.context, '6.0.0-alpha01', self.manifest, self.needs,
                                             str(self.executions), self.SHARDS)

    def test_a_complete_census_validates_the_single_forced_execution(self):
        record = self.validate()
        self.assertEqual(record['classification'], 'validated')
        self.assertEqual(record['sha'], self.sha)
        self.assertEqual(record['checks'], self.needs)
        self.assertEqual(record['lincheck_shards'], ['1/4', '2/4', '3/4', '4/4'])
        self.assertEqual(record['scenario_count'], CONTROL.LINCHECK_SCENARIO_COUNT)
        self.assertEqual(record['scenario_digest'], CONTROL.LINCHECK_SCENARIO_DIGEST)
        self.assertEqual(len(record['executions']), self.SHARDS + 1)

    def test_two_digit_shard_counts_are_compared_by_shard_not_by_string_order(self):
        shutil.rmtree(self.executions / 'full-jvm-results-lincheck-1')
        for index in range(2, self.SHARDS + 1):
            shutil.rmtree(self.executions / ('full-jvm-results-lincheck-' + str(index)))
        self.SHARDS = 10
        for index in range(1, 11):
            self.write_shard(index)
        self.assertEqual(self.validate()['lincheck_shards'][-1], '10/10')

    def test_a_missing_shard_is_refused(self):
        (self.executions / 'full-jvm-results-lincheck-3' / 'full-suite-evidence' / 'execution.json').unlink()
        with self.assertRaisesRegex(ValueError, '3/4'):
            self.validate()

    def test_a_missing_jvm_execution_is_refused(self):
        (self.executions / 'full-jvm-results-jvmTest' / 'full-suite-evidence' / 'execution.json').unlink()
        with self.assertRaisesRegex(ValueError, 'jvmTest'):
            self.validate()

    def test_no_archived_execution_at_all_is_refused(self):
        shutil.rmtree(self.executions)
        with self.assertRaisesRegex(ValueError, 'execution'):
            self.validate()

    def test_a_shard_not_classified_passed_is_refused(self):
        for classification in ['unexecuted', 'infrastructure-or-incomplete', 'test-failure']:
            with self.subTest(classification=classification):
                self.write_shard(2, classification=classification)
                with self.assertRaisesRegex(ValueError, classification):
                    self.validate()
        self.write_shard(2)
        self.validate()

    def test_scenario_indices_short_of_the_whole_plan_are_refused(self):
        self.write_shard(4, scenario_indices=CONTROL.shard_indices('4/4')[:-1])
        with self.assertRaisesRegex(ValueError, 'scenario'):
            self.validate()

    def test_overlapping_scenario_indices_are_refused(self):
        self.write_shard(4, scenario_indices=CONTROL.shard_indices('3/4'))
        with self.assertRaisesRegex(ValueError, 'scenario'):
            self.validate()

    def test_shards_that_do_not_all_carry_the_pinned_plan_digest_are_refused(self):
        for digest in ['f' * 16, None, '']:
            with self.subTest(digest=digest):
                self.write_shard(2, scenario_digest=digest)
                with self.assertRaisesRegex(ValueError, CONTROL.LINCHECK_SCENARIO_DIGEST):
                    self.validate()
        self.write_shard(2)
        self.validate()

    def test_a_record_from_an_unknown_task_is_refused(self):
        self.write_execution('stray', task=':mutations:someOtherTest', shard=None)
        with self.assertRaisesRegex(ValueError, 'someOtherTest'):
            self.validate()

    def test_a_record_with_no_task_at_all_is_refused(self):
        self.write_execution('stray', shard=None)
        with self.assertRaisesRegex(ValueError, 'lane|task'):
            self.validate()

    def test_jvm_results_containing_the_lincheck_class_are_refused(self):
        self.write_jvm(executed_classes=['example.ExampleTest', CONTROL.LINCHECK_CLASS])
        with self.assertRaisesRegex(ValueError, CONTROL.LINCHECK_CLASS):
            self.validate()

    def test_jvm_results_missing_a_suite_class_are_refused(self):
        self.write_jvm(executed_classes=[])
        with self.assertRaisesRegex(ValueError, 'example.ExampleTest'):
            self.validate()

    def test_a_shard_without_a_passed_lincheck_testcase_is_refused(self):
        for identifiers in [[], [dict(id=CONTROL.LINCHECK_CLASS + '#x', outcome='skipped')]]:
            with self.subTest(identifiers=identifiers):
                self.write_shard(1, test_identifiers=identifiers)
                with self.assertRaisesRegex(ValueError, '1/4'):
                    self.validate()

    def test_provenance_drift_across_shards_is_refused(self):
        for field, value in [('source_sha', 'b' * 40), ('checked_out_sha', 'b' * 40),
                             ('run_id', '456'), ('run_attempt', '2'), ('version', '6.0.0-alpha02')]:
            with self.subTest(field=field):
                self.write_shard(3, **{field: value})
                with self.assertRaisesRegex(ValueError, 'provenance|SHA|version'):
                    self.validate()
        self.write_shard(3)
        self.validate()

    def test_leaf_job_provenance_is_still_required(self):
        for name in self.manifest['full_suite_jobs']:
            with self.subTest(job=name):
                needs = copy.deepcopy(self.needs)
                needs[name]['outputs']['run_attempt'] = '0'
                with self.assertRaisesRegex(ValueError, 'provenance'):
                    CONTROL.full_suite_validation(self.context, '6.0.0-alpha01', self.manifest, needs,
                                                  str(self.executions), self.SHARDS)
                needs = copy.deepcopy(self.needs)
                needs[name]['result'] = 'failure'
                with self.assertRaisesRegex(ValueError, 'success'):
                    CONTROL.full_suite_validation(self.context, '6.0.0-alpha01', self.manifest, needs,
                                                  str(self.executions), self.SHARDS)


if __name__ == '__main__':
    unittest.main()
