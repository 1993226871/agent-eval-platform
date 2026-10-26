#!/usr/bin/env bash
# Only a disposable GitHub-hosted VM may run this provisioning helper.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true || "${RUNNER_ENVIRONMENT:-}" != github-hosted || "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo 'Refusing runtime provisioning outside an ephemeral GitHub-hosted Linux x86_64 VM.' >&2
  exit 1
fi
task_download=$(mktemp -d)
trap 'rm -rf -- "$task_download"' EXIT
curl --fail --location --retry 3 --max-time 180 \
  https://github.com/google/gvisor/releases/download/release-20261019.0/gvisor-x86_64.tar.bz2 \
  --output "$task_download/gvisor.tar.bz2"
printf '%s  %s\n' '7cbc2aeeee809dcccfb40e612504a1b76f2c119790cee59e482f7b35428cccc4f71705e7e322442d07d1f7d29b5d9e72e31319fc3088d6dcbec9fc7c1f0d8881' "$task_download/gvisor.tar.bz2" | sha512sum --check
sudo tar -xjf "$task_download/gvisor.tar.bz2" -C /usr/local/bin
sudo /usr/local/bin/runsc install
sudo mkdir -p /tmp/agenteval-runsc-panic
sudo python3 - <<'PY'
import json
from pathlib import Path
path = Path('/etc/docker/daemon.json')
config = json.loads(path.read_text())
runtime = config['runtimes']['runsc']
runtime['runtimeArgs'] = runtime.get('runtimeArgs', []) + ['--panic-log=/tmp/agenteval-runsc-panic/']
path.write_text(json.dumps(config))
PY
sudo systemctl restart docker
/usr/local/bin/runsc --version
docker info --format '{{json .Runtimes}}'
