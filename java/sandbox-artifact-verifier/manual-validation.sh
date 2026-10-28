#!/usr/bin/env bash
# Manual only. Requires the reviewed shared integration to be committed first.
set -euo pipefail
umask 077

repo=/workspace/tracepermit-sandbox-artifact-verifier
base=dfb7b3b11ed7af3b4d0230fae9adf6e514289ba5
expected_branch=${EXPECTED_BRANCH:-codex/sandbox-artifact-verifier}
stage=${1:-}
log_dir=$(mktemp -d /tmp/tracepermit-sandbox-verifier.XXXXXX)
printf 'Private evidence directory: %s\n' "$log_dir"
exec >"$log_dir/runner.stdout" 2>"$log_dir/runner.stderr"
trap 'result=$?; printf "%s\n" "$result" >"$log_dir/overall.exit"' EXIT
[[ "$stage" == focused || "$stage" == repository ]] || { printf 'Specify focused or repository.\n' >&2; exit 2; }
[[ ${EXPECTED_HEAD:-} =~ ^[0-9a-f]{40}$ && ${EXPECTED_TREE:-} =~ ^[0-9a-f]{40}$ ]] || {
  printf 'Supply independently reviewed EXPECTED_HEAD and EXPECTED_TREE for the integrated commit.\n' >&2; exit 2;
}
cd "$repo"
[[ $(git rev-parse --show-toplevel) == "$repo" && $(git branch --show-current) == "$expected_branch" ]] || exit 2
[[ $(git rev-parse HEAD) == "$EXPECTED_HEAD" && $(git rev-parse 'HEAD^{tree}') == "$EXPECTED_TREE" ]] || exit 2
git merge-base --is-ancestor "$base" HEAD || exit 2
[[ -z $(git status --porcelain --untracked-files=all) && ! -e agentcore-work ]] || exit 2
# The root settings change must be reviewed, applied and committed first.
rg -q '^  "sandbox-artifact-verifier",$' java/settings.gradle.kts || {
  printf 'Shared settings integration is missing. Do not run Gradle yet.\n' >&2; exit 2;
}
export TRACEPERMIT_JAVA_HOME=/workspace/.local/jdk-21
export TRACEPERMIT_GRADLE_USER_HOME=/workspace/tracepermit-agentcore-registration-http/.cache/gradle
export COREPACK_ENABLE_NETWORK=0
[[ -x "$TRACEPERMIT_JAVA_HOME/bin/java" && -x "$TRACEPERMIT_JAVA_HOME/bin/javac" ]] || exit 2
[[ $("$TRACEPERMIT_JAVA_HOME/bin/java" -version 2>&1 | head -n 1) == *'version "21'* ]] || exit 2
[[ $("$TRACEPERMIT_JAVA_HOME/bin/javac" -version 2>&1) == 'javac 21'* ]] || exit 2
shopt -s nullglob
dists=("$TRACEPERMIT_GRADLE_USER_HOME"/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle)
[[ ${#dists[@]} -eq 1 && -x ${dists[0]} ]] || { printf 'Prepared Gradle 8.14.3 distribution missing.\n' >&2; exit 2; }
gradle=${dists[0]}
source scripts/configure-java-environment.sh
export GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.caching=false -Dorg.gradle.workers.max=1"
printf '%s\n' "$EXPECTED_HEAD" >"$log_dir/head"
printf '%s\n' "$EXPECTED_TREE" >"$log_dir/tree"
touch "$log_dir/start"
run() {
  local label=$1; shift
  printf '%q ' "$@" >"$log_dir/$label.command"; printf '\n' >>"$log_dir/$label.command"
  set +e
  "$@" >"$log_dir/$label.stdout" 2>"$log_dir/$label.stderr"
  local result=$?
  set -e
  printf '%s\n' "$result" >"$log_dir/$label.exit"
  printf '%s exit=%s\n' "$label" "$result"
  if [[ $result -ne 0 ]]; then exit "$result"; fi
}
gradle_args=(-p java --offline --no-build-cache --no-configuration-cache --max-workers=1 --rerun-tasks)
if [[ "$stage" == focused ]]; then
  run focused perl scripts/owned-test-reaper.pl "$gradle" "${gradle_args[@]}" :sandbox-artifact-verifier:test \
    --tests io.tracepermit.sandbox.verifier.TarVerifierTest \
    --tests io.tracepermit.sandbox.verifier.EvidenceVerifierTest \
    --tests io.tracepermit.sandbox.verifier.SandboxArtifactVerifierTest
  run focused-xml python3 - "$repo" "$log_dir/start" <<'PY'
from pathlib import Path
import sys,xml.etree.ElementTree as E
base=Path(sys.argv[1])/'java/sandbox-artifact-verifier/build/test-results/test'
start=Path(sys.argv[2]).stat().st_mtime_ns
expected={'TarVerifierTest':9,'EvidenceVerifierTest':11,'SandboxArtifactVerifierTest':3}
for name,count in expected.items():
 p=base/('TEST-io.tracepermit.sandbox.verifier.'+name+'.xml')
 if not p.is_file() or p.stat().st_mtime_ns < start: raise SystemExit('Missing or stale focused XML')
 r=E.parse(p).getroot()
 if int(r.get('tests','0'))!=count or any(int(r.get(k,'0')) for k in ('failures','errors','skipped')):
  raise SystemExit('Focused selector count or result mismatch')
 print(name, count, 'fresh, no failures or skips')
PY
else
  # Prepared dependencies only; no package installation or image pulls.
  [[ -d node_modules && ! -L node_modules && -d apps/console/node_modules && ! -L apps/console/node_modules ]] || {
    printf 'Prepared real node_modules directories are missing; do not install.\n' >&2; exit 2;
  }
  for image in postgres:16.4-alpine mariadb:11.4.13 testcontainers/ryuk:0.12.0; do
    env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
      docker --host=unix:///var/run/docker.sock image inspect "$image" > /dev/null 2>&1 || {
      printf 'Required prepared Docker image missing.\n' >&2; exit 2;
    }
  done
  run console-build pnpm build
  run console-types pnpm typecheck
  run console-tests pnpm test
  run java-env pnpm java:env-check
  run reaper-controls node --test scripts/owned-test-reaper.test.mjs
  run java-full perl scripts/owned-test-reaper.pl "$gradle" "${gradle_args[@]}" checkAll
  run test-xml python3 - "$repo" "$log_dir/start" <<'PY'
from pathlib import Path
import sys,xml.etree.ElementTree as E
root=Path(sys.argv[1])/'java'; start=Path(sys.argv[2]).stat().st_mtime_ns
files=list(root.glob('*/build/test-results/test/TEST-*.xml'))
if not files: raise SystemExit('No Java test reports')
totals={'tests':0,'failures':0,'errors':0,'skipped':0}
optional=('io.tracepermit.adapters.http.JavaBackendEndpointConnectorE2eTest',
          'installedArchiveConnectorAgainstRealBackendWithNativePty()')
for p in files:
 if p.stat().st_mtime_ns < start: raise SystemExit('Stale test report')
 r=E.parse(p).getroot()
 for key in totals: totals[key]+=int(r.get(key,'0'))
 for case in r.findall('testcase'):
  if case.find('skipped') is not None:
   ident=(case.get('classname'),case.get('name'))
   print('SKIPPED',*ident)
   if ident!=optional: raise SystemExit('Unexpected skip')
if totals['failures'] or totals['errors']: raise SystemExit('Java tests failed')
module=root/'sandbox-artifact-verifier/build/test-results/test'
for name,count in {'TarVerifierTest':9,'EvidenceVerifierTest':11,'SandboxArtifactVerifierTest':3}.items():
 p=module/('TEST-io.tracepermit.sandbox.verifier.'+name+'.xml')
 if not p.is_file() or p.stat().st_mtime_ns < start: raise SystemExit('Verifier report missing or stale')
 r=E.parse(p).getroot()
 if int(r.get('tests','0'))!=count or any(int(r.get(k,'0')) for k in ('failures','errors','skipped')):
  raise SystemExit('Verifier count or result mismatch')
print('Java test totals',totals,'all reports fresh; Gradle rerun-tasks and build cache disabled')
PY
  run coverage-xml python3 - "$repo" "$log_dir/start" <<'PY'
from pathlib import Path
import sys,xml.etree.ElementTree as E
root=Path(sys.argv[1]); start=Path(sys.argv[2]).stat().st_mtime_ns
p=root/'java/build/reports/jacoco/jacocoAggregateReport/jacocoAggregateReport.xml'
if not p.is_file() or p.stat().st_mtime_ns < start: raise SystemExit('Missing or stale aggregate coverage')
report=E.parse(p).getroot()
for kind,minimum in [('LINE',94),('BRANCH',75)]:
 matches=[x for x in report.findall('counter') if x.get('type')==kind]
 if len(matches)!=1: raise SystemExit('Aggregate counter missing')
 covered=int(matches[0].get('covered')); missed=int(matches[0].get('missed')); total=covered+missed
 if total==0 or covered*100<minimum*total: raise SystemExit('Aggregate floor failed')
 print(kind,covered,missed,round(100*covered/total,4))
PY
  run format pnpm format:check
  run docs-links pnpm docs:links
  run docs-flows pnpm docs:flows:check
  run diff git diff --check "$base"
fi
printf 'Completed %s stage at %s; inspect private logs and XML.\n' "$stage" "$log_dir"
