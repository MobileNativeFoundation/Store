import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]

# The full mutations JVM suite is two Gradle tasks: every other test class in :mutations:jvmTest,
# and the Lincheck model-checking class alone in :mutations:lincheckTest, sharded across jobs.
LINCHECK_CLASS = 'org.mobilenativefoundation.store6.mutations.MutationJournalLincheckTest'
JVM_SUITE_TASK = ':mutations:jvmTest'
LINCHECK_TASK = ':mutations:lincheckTest'
# Mirrors LincheckScenarioPlan.SCENARIO_COUNT, .SCENARIO_DIGEST and .SCENARIO_MARKER;
# test_workflow_contract pins these to the Kotlin source so the two cannot drift apart silently.
# The digest is the golden hash of the whole scenario plan: every shard prints it, and a release
# is refused unless all of them validated this exact plan. That is what makes the Kotlin
# SCENARIO_SEED a guarded lever rather than a silent one.
LINCHECK_SCENARIO_COUNT = 101
LINCHECK_SCENARIO_DIGEST = '353a057d5f715bc8'
SCENARIO_MARKER = 'store6-lincheck-scenarios'
SCENARIO_MARKER_FORM = re.compile(
    r'[ \t]*' + re.escape(SCENARIO_MARKER) +
    r' shard=(\d+/\d+) count=(\d+) indices=([0-9,]*) digest=([0-9a-f]+)')
# Lincheck's own Reporter.logIteration prints this once per scenario at LoggingLevel.INFO. The
# marker states the plan; these lines are what the model checker actually ran.
ITERATION_FORM = re.compile(r'[ \t]*= Iteration (\d+) / (\d+) =')


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    temporary.replace(path)


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            if key.strip() in result:
                raise ValueError(f'duplicate property: {key.strip()}')
            result[key.strip()] = value.strip()
    return result


def root_version(root):
    version = properties(root / 'gradle.properties')['VERSION_NAME']
    if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]*', version):
        raise ValueError('invalid root version')
    for path in root.glob('*/gradle.properties'):
        if 'VERSION_NAME' in properties(path):
            raise ValueError(f'version override in {path}')
    return version


def validate_jobs(needs, required):
    missing = set(required) - set(needs)
    if missing:
        raise ValueError('missing validation jobs: ' + ', '.join(sorted(missing)))
    for name in required:
        if needs[name].get('result') != 'success':
            raise ValueError(f'{name} must finish with success')


def validate_job_provenance(context, version, needs, required):
    validate_jobs(needs, required)
    for name in required:
        output = needs[name].get('outputs', {})
        if output.get('source_sha') != context['sha']:
            raise ValueError(f'{name} validated a different SHA')
        if any(str(output.get(key)) != str(context[key]) for key in ['run_id', 'run_attempt']):
            raise ValueError(f'{name} has different run provenance')
        if output.get('version') != version:
            raise ValueError(f'{name} validated a different version')


def validate_context(context, version, manifest):
    if context['repository'] != manifest['repository']:
        raise ValueError('publication repository is forbidden')
    if not re.fullmatch(r'[0-9a-f]{40}', context['sha']) or context['sha'] != context['checked_out_sha']:
        raise ValueError('checked-out SHA does not match the workflow SHA')
    if not all(str(context[key]).isdigit() for key in ['run_id', 'run_attempt']):
        raise ValueError('missing run provenance')
    if context['event'] == 'push' and context['ref'].startswith('refs/tags/v'):
        if version.endswith('-SNAPSHOT'):
            raise ValueError('SNAPSHOT tags cannot publish')
        if context['ref'] != f'refs/tags/v{version}':
            raise ValueError('tag and root version differ')
    elif context['event'] == 'workflow_dispatch' and context['ref'].startswith('refs/heads/'):
        if not version.endswith('-SNAPSHOT'):
            raise ValueError('workflow_dispatch publishes snapshots only')
    else:
        raise ValueError('unsupported publication event or ref')


def release_evidence(context, version, manifest, needs):
    validate_context(context, version, manifest)
    validate_job_provenance(context, version, needs, manifest['release_jobs'])
    return dict(schema_version=1, source_sha=context['sha'], version=version,
                repository=context['repository'], ref=context['ref'],
                run_id=context['run_id'], run_attempt=context['run_attempt'],
                classification='validated', artifacts=manifest['artifacts'],
                checks=needs, matrix_jobs=manifest['matrix_jobs'])


def release_notes(path, version):
    text = path.read_text()
    sections = re.split(r'(?m)^## ', text)
    selected = [section for section in sections[1:] if section.startswith(f'[{version}]')]
    if len(selected) != 1:
        raise ValueError(f'exactly one release-note section required for {version}')
    heading, _, body = selected[0].partition('\n')
    if not re.fullmatch(r'\[' + re.escape(version) + r'\] \(\d{4}-\d{2}-\d{2}\)', heading.strip()):
        raise ValueError('release notes need a dated version heading')
    if not body.strip() or re.search(r'\b(TODO|TBD|FIXME)\b|\[Unreleased\]', body):
        raise ValueError('release notes are empty or contain placeholders')
    return '## ' + selected[0].strip() + '\n'


def require_new_release(existing):
    if existing is not None:
        raise ValueError('A release record already exists. Inspect its receipt and use record repair; do not republish.')


def publish_modules(record, path, publish):
    if Path(path).exists():
        raise ValueError('publication receipt already exists; inspect and repair instead of republishing')
    receipt = dict(record, publication_status='started', published_modules=[])
    write_json(path, receipt)
    for module in record['artifacts']:
        receipt['attempting_module'] = module
        write_json(path, receipt)
        task = 'publishToMavenCentral' if record['version'].endswith('-SNAPSHOT') else 'publishAndReleaseToMavenCentral'
        try:
            publish(f':{module}:{task}')
        except BaseException:
            receipt['publication_status'] = 'partial'
            write_json(path, receipt)
            raise
        receipt['published_modules'].append(module)
        receipt.pop('attempting_module')
        write_json(path, receipt)
    receipt['publication_status'] = 'complete'
    write_json(path, receipt)
    return receipt


def repair_record(receipt, update):
    if receipt.get('publication_status') != 'complete' or receipt.get('published_modules') != receipt.get('artifacts'):
        raise ValueError('Maven publication receipt is incomplete; reconcile Central before record repair')
    update(receipt)


def publication_versions(repository, version, group, modules, artifacts, expected_publications=None):
    if not expected_publications or len(expected_publications) != len(set(expected_publications)):
        raise ValueError('an explicit, nonduplicated expected publication inventory is required')
    if not set(modules).issubset(expected_publications):
        raise ValueError('expected publication inventory omits a module root')
    discovered = {path.name for path in repository.iterdir() if (path / version).is_dir()
                  and any(path.name == module or path.name.startswith(module + '-') for module in modules)}
    unexpected = discovered - set(expected_publications)
    if unexpected:
        raise ValueError('unexpected publication outside the inventory: ' + ', '.join(sorted(unexpected)))
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    count = 0
    for artifact in expected_publications:
        pom = repository / artifact / version / f'{artifact}-{version}.pom'
        if not pom.is_file():
            raise ValueError(f'missing publication POM: {pom}')
        root = ET.parse(pom).getroot()
        if root.findtext('m:version', namespaces=ns) != version:
            raise ValueError(f'publication version differs in {pom}')
        if root.findtext('m:groupId', namespaces=ns) != group:
            raise ValueError(f'publication group differs in {pom}')
        if root.findtext('m:artifactId', namespaces=ns) != artifact:
            raise ValueError(f'publication artifact differs in {pom}')
        dependencies = root.findall('.//m:dependency', ns)
        for dependency in dependencies:
            if dependency.findtext('m:groupId', namespaces=ns) == group:
                if dependency.findtext('m:version', namespaces=ns) != version:
                    raise ValueError(f'internal dependency version differs in {pom}')
        if artifact == 'bom':
            constraints = root.findall('m:dependencyManagement/m:dependencies/m:dependency', ns)
            names = [dependency.findtext('m:artifactId', namespaces=ns) for dependency in constraints]
            if sorted(names) != sorted(set(artifacts) - {'bom'}):
                raise ValueError('BOM inventory differs from the publication allowlist')
        metadata = pom.with_suffix('.module')
        if not metadata.is_file():
            raise ValueError(f'missing Gradle module metadata: {metadata}')
        data = json.loads(metadata.read_text())
        component = data.get('component', {})
        component_module = component.get('module')
        if component_module == artifact:
            if 'url' in component:
                raise ValueError(f'unexpected self-component URL in {metadata}')
        else:
            owners = [module for module in modules if artifact.startswith(module + '-')]
            owner = max(owners, key=len) if owners else None
            if owner is None or component_module != owner or artifact in modules:
                raise ValueError(f'Gradle component owner differs in {metadata}')
            # KMP target metadata identifies its root component through this relative URL.
            expected_url = f'../../{owner}/{version}/{owner}-{version}.module'
            if component.get('url') != expected_url:
                raise ValueError(f'Gradle component URL differs in {metadata}')
            owner_metadata = (metadata.parent / expected_url).resolve()
            if not owner_metadata.is_relative_to(repository.resolve()):
                raise ValueError(f'Gradle component URL escapes the repository in {metadata}')
            if not owner_metadata.is_file():
                raise ValueError(f'missing owning Gradle module metadata: {owner_metadata}')
        if any(component.get(key) != value for key, value in
               dict(group=group, module=component_module, version=version).items()):
            raise ValueError(f'Gradle module coordinates differ in {metadata}')
        for variant in data.get('variants', []):
            for dependency in variant.get('dependencies', []) + variant.get('dependencyConstraints', []):
                if dependency.get('group') == group:
                    constraint = dependency.get('version', {})
                    if not constraint or any(value != version for key, value in constraint.items()
                                             if key in ['requires', 'strictly', 'prefers']):
                        raise ValueError(f'Gradle dependency version differs in {metadata}')
            available = variant.get('available-at', {})
            if available.get('group') == group and available.get('version') != version:
                raise ValueError(f'Gradle target version differs in {metadata}')
        count += 1
    return count


def task_outcome(log, task):
    matches = re.findall(r'^> Task ' + re.escape(task) + r'(?:[ \t]+([^\r\n]*))?\r?$', log, re.MULTILINE)
    outcomes = [match.strip() for match in matches]
    if not outcomes or any(outcome not in ['', 'FAILED'] for outcome in outcomes):
        raise ValueError(f'expected an actually executed {task} task')
    return 'failed' if 'FAILED' in outcomes else 'executed'


def shard_indices(shard):
    match = re.fullmatch(r'(\d+)/(\d+)', shard or '')
    index, count = (int(match[1]), int(match[2])) if match else (0, 0)
    if not 1 <= index <= count <= LINCHECK_SCENARIO_COUNT:
        raise ValueError(f"a Lincheck shard must be k/N with 1 <= k <= N <= {LINCHECK_SCENARIO_COUNT}; "
                         f"got '{shard}'")
    return [value for value in range(LINCHECK_SCENARIO_COUNT) if value % count == index - 1]


def standard_output(log, results, log_path):
    """Everything the shard printed: the Gradle console log and every result XML's system-out."""
    sources = [(log, str(log_path))]
    for path in sorted(results.glob('TEST-*.xml')):
        for stream in ET.parse(path).getroot().iter('system-out'):
            sources.append((''.join(stream.itertext()), path.name))
    return sources


def scenario_indices(log, results, shard, log_path):
    """The shard prints its plan; both the console log and the result XML must agree with it."""
    reported = set()
    for text, _ in standard_output(log, results, log_path):
        for line in text.split('\n'):
            match = SCENARIO_MARKER_FORM.fullmatch(line.rstrip('\r'))
            if match:
                reported.add(match.group(1, 2, 3, 4))
    if not reported:
        raise ValueError(f'no {SCENARIO_MARKER} evidence for {shard}; the shard did not report its plan')
    if len(reported) != 1:
        raise ValueError(f'conflicting {SCENARIO_MARKER} evidence: ' + '; '.join(sorted(str(x) for x in reported)))
    executed, count, joined, digest = reported.pop()
    indices = [int(value) for value in joined.split(',')] if joined else []
    if executed != shard:
        raise ValueError(f'shard {shard} reported scenarios for {executed}')
    if int(count) != len(indices) or sorted(set(indices)) != indices:
        raise ValueError(f'shard {shard} reported {count} scenarios as {joined}')
    if indices != shard_indices(shard):
        raise ValueError(f'shard {shard} executed scenarios outside its partition of the plan')
    if digest != LINCHECK_SCENARIO_DIGEST:
        raise ValueError(f'shard {shard} validated plan digest {digest}, not the pinned '
                         f'{LINCHECK_SCENARIO_DIGEST}; the scenario plan changed')
    return indices, digest


def executed_iterations(log, results, shard, log_path):
    """How many scenarios Lincheck reported running, and how many it was configured to run."""
    reported = set()
    for text, _ in standard_output(log, results, log_path):
        for line in text.split('\n'):
            match = ITERATION_FORM.fullmatch(line.rstrip('\r'))
            if match:
                reported.add((int(match[1]), int(match[2])))
    if not reported:
        raise ValueError(f'no Lincheck iteration evidence for shard {shard}; the model checker did '
                         f'not report the scenarios it ran')
    planned = {total for _, total in reported}
    if len(planned) != 1:
        raise ValueError(f'shard {shard} reported conflicting Lincheck iteration totals: ' +
                         ', '.join(str(total) for total in sorted(planned)))
    executed = sorted(index for index, _ in reported)
    if executed != list(range(1, len(executed) + 1)):
        raise ValueError(f'shard {shard} reported Lincheck iterations that are not a prefix of its '
                         f'plan: {", ".join(str(index) for index in executed)}')
    return len(executed), planned.pop()


def suite_classes(root):
    """Every mutations test class the sources declare, taken as the census of the full suite."""
    expected = []
    for source in sorted((root / 'mutations/src').glob('**/*Test.kt')):
        if '/commonTest/' in str(source) or '/jvmTest/' in str(source):
            package = re.search(r'(?m)^package (\S+)', source.read_text())
            if package:
                expected.append(package[1] + '.' + source.stem)
    return expected


def executed_classes(results):
    executed = set()
    for path in sorted(results.glob('TEST-*.xml')):
        for testcase in ET.parse(path).getroot().iter('testcase'):
            if testcase.find('skipped') is None:
                executed.add(testcase.get('classname', ''))
    return executed


def test_identifiers(results, expected):
    identifiers = []
    executed = set()
    for path in sorted(results.glob('TEST-*.xml')):
        for testcase in ET.parse(path).getroot().iter('testcase'):
            classname = testcase.get('classname', '')
            outcome = 'passed'
            if testcase.find('skipped') is not None:
                outcome = 'skipped'
            elif testcase.find('failure') is not None or testcase.find('error') is not None:
                outcome = 'failed'
            if outcome != 'skipped':
                executed.add(classname)
            identifiers.append(dict(id=classname + '#' + testcase.get('name', ''), outcome=outcome))
    missing = set(expected) - executed
    if missing or not identifiers:
        raise ValueError('no executed testcase evidence for: ' + ', '.join(sorted(missing)))
    return identifiers


def context_from_env():
    return dict(repository=os.environ['GITHUB_REPOSITORY'], event=os.environ['GITHUB_EVENT_NAME'],
                ref=os.environ['GITHUB_REF'], sha=os.environ['GITHUB_SHA'],
                checked_out_sha=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                run_id=os.environ['GITHUB_RUN_ID'], run_attempt=os.environ['GITHUB_RUN_ATTEMPT'])


def append_outputs(context, version):
    if context['sha'] != context['checked_out_sha']:
        raise ValueError('checked-out SHA changed')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in [('source_sha', context['sha']), ('run_id', context['run_id']),
                           ('run_attempt', context['run_attempt']), ('version', version)]:
            output.write(f'{key}={value}\n')


def instrumentation_failures(log, results, log_path):
    failures = []

    def collect(text, source, path, stream_index=None):
        for line_number, line in enumerate(text.split('\n'), 1):
            match = re.fullmatch(r"[ \t]*Unable to transform (.+)", line)
            if match:
                failure = dict(source=source, path=path, line=line_number, class_name=match[1])
                if stream_index is not None:
                    failure['stream_index'] = stream_index
                failures.append(failure)

    collect(log, 'gradle-log', str(log_path))
    for path in sorted(results.glob('TEST-*.xml')):
        for index, stream in enumerate(ET.parse(path).getroot().iter('system-err'), 1):
            collect(''.join(stream.itertext()), 'xml-system-err', path.name, index)
    return failures


def full_suite_record(args, context, manifest):
    output = Path(args.output)
    log = Path(args.log).read_text()
    version = root_version(ROOT)
    lincheck = args.task == 'lincheckTest'
    task = LINCHECK_TASK if lincheck else JVM_SUITE_TASK
    # No implicit whole-plan default here: on this lane "no shard" would be a third meaning next to
    # the absent Gradle property and an explicit 1/1, and a shard job that lost its matrix value
    # would silently claim the whole plan. The workflow always passes k/N.
    shard = (args.shard or '').strip() if lincheck else None
    record = dict(schema_version=1, source_sha=context['sha'], checked_out_sha=context['checked_out_sha'], version=version,
                  repository=context['repository'], run_id=context['run_id'], run_attempt=context['run_attempt'],
                  task=task, shard=shard, gradle_exit_code=args.exit_code,
                  classification='unexecuted', log_sha256=hashlib.sha256(log.encode()).hexdigest())
    try:
        if not lincheck and args.shard:
            raise ValueError(f'{JVM_SUITE_TASK} does not take a Lincheck shard')
        if lincheck and not shard:
            raise ValueError(f'{LINCHECK_TASK} requires an explicit --shard of the form k/N')
        record['task_outcome'] = task_outcome(log, task)
        record['classification'] = 'infrastructure-or-incomplete'
        suite = [name for name in suite_classes(ROOT) if name != LINCHECK_CLASS]
        expected = [LINCHECK_CLASS] if lincheck else suite
        forbidden = suite if lincheck else [LINCHECK_CLASS]
        record['expected_classes'] = expected
        results = Path(args.results)
        record['xml_files'] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in sorted(results.glob('TEST-*.xml'))}
        record['test_identifiers'] = test_identifiers(results, [])
        if any(item['outcome'] == 'failed' for item in record['test_identifiers']):
            record['classification'] = 'test-failure'
        test_identifiers(results, expected)
        record['executed_classes'] = sorted(executed_classes(results))
        trespassing = sorted(set(forbidden) & set(record['executed_classes']))
        if trespassing:
            raise ValueError(f'{task} executed {", ".join(trespassing)}, which belongs to the other lane')
        if lincheck:
            record['scenario_indices'], record['scenario_digest'] = \
                scenario_indices(log, results, shard, args.log)
            executed, planned = executed_iterations(log, results, shard, args.log)
            record['executed_iterations'] = executed
            if planned != len(record['scenario_indices']):
                raise ValueError(f'shard {shard} planned {len(record["scenario_indices"])} scenarios '
                                 f'but Lincheck was configured for {planned} iterations')
            # A failing shard stops at the failure, so a short count is expected there and the
            # recorded number is diagnostic rather than a second, misleading error.
            if record['classification'] != 'test-failure' and executed != planned:
                raise ValueError(f'shard {shard} planned {planned} scenarios but Lincheck reported '
                                 f'{executed} iterations')
        record['instrumentation_failures'] = instrumentation_failures(log, results, args.log)
        if record['instrumentation_failures']:
            record['evidence_error'] = 'Lincheck reported a bytecode transformation failure'
        if context['sha'] != context['checked_out_sha']:
            raise ValueError('source SHA changed during test execution')
        # A real test failure keeps its own classification; the guards below distinguish the ways a
        # run can fail to be evidence at all.
        if record['classification'] != 'test-failure':
            if record['instrumentation_failures']:
                raise ValueError(record['evidence_error'])
            if args.exit_code != 0 or record['task_outcome'] != 'executed':
                raise ValueError('Gradle failed without test failure evidence')
            record['classification'] = 'passed'
    except (ValueError, OSError, ET.ParseError) as error:
        record['evidence_error'] = str(error)
    write_json(output, record)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as handle:
            handle.write(f"### Full mutations JVM execution: {task}{' shard ' + shard if shard else ''}\n\n"
                         f"Classification: **{record['classification']}**. Source: `{context['sha']}`. "
                         f"Run: `{context['run_id']}`, attempt: `{context['run_attempt']}`.\n\n"
                         f"Task outcome: `{record.get('task_outcome', 'unexecuted')}`. "
                         f"Gradle exit: `{args.exit_code}`.\n\n"
                         "The result artifact contains the console log, XML, test identifiers and hashes. "
                         "For a failure, preserve this first result, classify the failed test or infrastructure cause, "
                         "and link the fix or disposition from this run. Do not rerun unchanged failures for green.\n")
    if record['classification'] != 'passed':
        raise ValueError('full-suite evidence did not pass; inspect the archived first result')
    append_outputs(context, version)


def full_suite_validation(context, version, manifest, needs, executions, shards):
    """Prove that one forced execution of the whole suite happened, with no lane and no scenario lost."""
    validate_job_provenance(context, version, needs, manifest['full_suite_jobs'])
    if not isinstance(shards, int) or shards < 1:
        raise ValueError('the Lincheck shard count must be a positive integer')
    records = [json.loads(path.read_text())
               for path in sorted(Path(executions).glob('**/execution.json'))]
    if not records:
        raise ValueError(f'no full-suite execution record was archived under {executions}')
    for record in records:
        name = str(record.get('task')) + (' shard ' + str(record['shard']) if record.get('shard') else '')
        # argparse cannot produce a third lane today, but an archived record is a file: refuse an
        # unknown one rather than letting it sit uncounted in the census.
        if record.get('task') not in [JVM_SUITE_TASK, LINCHECK_TASK]:
            raise ValueError(f'{name} is not a full-suite lane; expected {JVM_SUITE_TASK} '
                             f'or {LINCHECK_TASK}')
        if record.get('classification') != 'passed':
            raise ValueError(f"{name} is classified {record.get('classification')}, not passed")
        if record.get('source_sha') != context['sha'] or record.get('checked_out_sha') != context['sha']:
            raise ValueError(f'{name} validated a different SHA')
        if any(str(record.get(key)) != str(context[key]) for key in ['run_id', 'run_attempt']):
            raise ValueError(f'{name} has different run provenance')
        if record.get('version') != version:
            raise ValueError(f'{name} validated a different version')

    suite = [name for name in suite_classes(ROOT) if name != LINCHECK_CLASS]
    jvm = [record for record in records if record.get('task') == JVM_SUITE_TASK]
    if len(jvm) != 1:
        raise ValueError(f'expected exactly one {JVM_SUITE_TASK} execution, found {len(jvm)}')
    executed = set(jvm[0].get('executed_classes') or [])
    missing = sorted(set(suite) - executed)
    if missing:
        raise ValueError(f'{JVM_SUITE_TASK} lost test classes: ' + ', '.join(missing))
    if LINCHECK_CLASS in executed:
        raise ValueError(f'{JVM_SUITE_TASK} executed {LINCHECK_CLASS}; the Lincheck lane owns it')

    lincheck = [record for record in records if record.get('task') == LINCHECK_TASK]
    expected_shards = [f'{index}/{shards}' for index in range(1, shards + 1)]
    observed = sorted(str(record.get('shard')) for record in lincheck)
    if observed != sorted(expected_shards):
        raise ValueError('expected Lincheck shards ' + ', '.join(expected_shards) +
                         '; found ' + (', '.join(observed) or 'none'))
    digests = {record.get('scenario_digest') for record in lincheck}
    if digests != {LINCHECK_SCENARIO_DIGEST}:
        raise ValueError(f'every Lincheck shard must report plan digest {LINCHECK_SCENARIO_DIGEST}; '
                         'found ' + ', '.join(sorted(str(digest) for digest in digests)))
    union = []
    for record in lincheck:
        shard = record['shard']
        if LINCHECK_CLASS not in set(record.get('executed_classes') or []):
            raise ValueError(f'shard {shard} did not execute {LINCHECK_CLASS}')
        if not any(item.get('id', '').startswith(LINCHECK_CLASS + '#') and item.get('outcome') == 'passed'
                   for item in record.get('test_identifiers') or []):
            raise ValueError(f'shard {shard} has no passed {LINCHECK_CLASS} testcase')
        union += record.get('scenario_indices') or []
    if sorted(union) != list(range(LINCHECK_SCENARIO_COUNT)):
        raise ValueError(f'the Lincheck shards must cover scenario 0..{LINCHECK_SCENARIO_COUNT - 1} '
                         f'exactly once; they covered {len(union)} with {len(set(union))} distinct')
    return dict(context, version=version, checks=needs, classification='validated',
                lincheck_shards=expected_shards, scenario_count=LINCHECK_SCENARIO_COUNT,
                scenario_digest=LINCHECK_SCENARIO_DIGEST,
                executions=[dict(task=record['task'], shard=record.get('shard'),
                                 classification=record['classification'],
                                 task_outcome=record.get('task_outcome'),
                                 log_sha256=record.get('log_sha256'),
                                 executed_classes=len(record.get('executed_classes') or []),
                                 executed_iterations=record.get('executed_iterations'),
                                 scenario_indices=record.get('scenario_indices'))
                            for record in sorted(records, key=lambda item: (item['task'], item.get('shard') or ''))])


def gh(*args, **kwargs):
    return subprocess.run(['gh', *args], check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['version', 'provenance', 'matrix', 'full-suite', 'gate', 'reserve',
                                           'publish', 'record', 'full-suite-execution', 'publication-versions'])
    parser.add_argument('--output', default='release-evidence.json')
    parser.add_argument('--receipt', default='publication-receipt.json')
    parser.add_argument('--repository')
    parser.add_argument('--modules', nargs='+')
    parser.add_argument('--publications', nargs='+')
    parser.add_argument('--log')
    parser.add_argument('--results')
    parser.add_argument('--task', choices=['jvmTest', 'lincheckTest'])
    parser.add_argument('--shard')
    parser.add_argument('--exit-code', type=int)
    args = parser.parse_args()
    # --task is shared with the commands that ignore it, so argparse cannot mark it required; the
    # lane that decides which census a record is measured against must never be inferred.
    if args.command == 'full-suite-execution' and args.task is None:
        parser.error('full-suite-execution requires --task')
    version = root_version(ROOT)
    manifest = json.loads((ROOT / '.github/release-manifest.json').read_text())
    if args.command == 'version':
        print(version)
        return
    if args.command == 'publication-versions':
        count = publication_versions(Path(args.repository), version, properties(ROOT / 'gradle.properties')['GROUP'],
                                     args.modules, manifest['artifacts'], args.publications)
        print(f'Validated {count} publication POMs and Gradle metadata files at version {version}')
        return
    context = context_from_env()
    if args.command == 'provenance':
        append_outputs(context, version)
        write_json(args.output, dict(context, version=version, classification='validated'))
    elif args.command == 'matrix':
        needs = json.loads(os.environ['VALIDATION_NEEDS'])
        validate_job_provenance(context, version, needs, manifest['matrix_jobs'])
        append_outputs(context, version)
        write_json(args.output, dict(context, version=version, checks=needs, classification='validated'))
    elif args.command == 'full-suite':
        record = full_suite_validation(context, version, manifest, json.loads(os.environ['VALIDATION_NEEDS']),
                                       os.environ['FULL_SUITE_EXECUTIONS'], int(os.environ['FULL_SUITE_SHARDS']))
        append_outputs(context, version)
        write_json(args.output, record)
    elif args.command == 'gate':
        record = release_evidence(context, version, manifest, json.loads(os.environ['VALIDATION_NEEDS']))
        if not version.endswith('-SNAPSHOT'):
            Path('release-notes.md').write_text(release_notes(ROOT / 'CHANGELOG.md', version))
        write_json(args.output, record)
    elif args.command == 'reserve':
        record = json.loads(Path(args.output).read_text())
        validate_context(context, version, manifest)
        if record['source_sha'] != context['sha'] or record['version'] != version:
            raise ValueError('release evidence version or SHA differs')
        if version.endswith('-SNAPSHOT'):
            return
        tag = f'v{version}'
        existing = subprocess.run(['gh', 'release', 'view', tag, '--repo', context['repository'], '--json', 'tagName'],
                                  text=True, capture_output=True)
        require_new_release(json.loads(existing.stdout) if existing.returncode == 0 else None)
        gh('release', 'create', tag, '--repo', context['repository'], '--draft', '--verify-tag',
           '--title', tag, '--notes-file', 'release-notes.md')
    elif args.command == 'publish':
        record = json.loads(Path(args.output).read_text())
        validate_context(context, version, manifest)
        if record['source_sha'] != context['sha'] or record['version'] != version:
            raise ValueError('release evidence version or SHA differs')
        publish_modules(record, args.receipt, lambda task: subprocess.run(['./gradlew', task, '--stacktrace'], check=True))
    elif args.command == 'record':
        receipt = json.loads(Path(args.receipt).read_text())
        if context['repository'] != manifest['repository'] or receipt['repository'] != context['repository']:
            raise ValueError('record repair repository differs')
        if receipt['source_sha'] != context['checked_out_sha'] or receipt['version'] != version:
            raise ValueError('record repair source SHA or version differs')
        if receipt['artifacts'] != manifest['artifacts'] or receipt['classification'] != 'validated':
            raise ValueError('record repair inventory or validation differs')
        if version.endswith('-SNAPSHOT'):
            repair_record(receipt, lambda _: None)
            return
        Path('release-notes.md').write_text(release_notes(ROOT / 'CHANGELOG.md', version))
        tag = f'v{version}'
        def update(saved):
            gh('release', 'upload', tag, args.receipt, '--clobber', '--repo', context['repository'])
            command = ['release', 'edit', tag, '--repo', context['repository'], '--draft=false',
                       '--notes-file', 'release-notes.md', '--prerelease=' + str('-' in version).lower()]
            gh(*command)
        repair_record(receipt, update)
    elif args.command == 'full-suite-execution':
        full_suite_record(args, context, manifest)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
