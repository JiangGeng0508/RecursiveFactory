"""Run the portal branch regression in a new world; fail on missing/failed completion or timeout."""
import argparse
from datetime import datetime
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--offline', action='store_true')
parser.add_argument('--timeout', type=int, default=300)
args = parser.parse_args()
stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
run_dir = root / 'run/immersive-portals' / ('audit-' + stamp)
run_dir.mkdir(parents=True)
(run_dir / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
(run_dir / 'server.properties').write_text(
    'server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nview-distance=2\nsimulation-distance=2\n'
    'level-type=minecraft:flat\ngenerate-structures=false\nlevel-seed=1\nspawn-protection=0\n'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n',
    encoding='utf-8')
log = root / 'build' / ('portal-audit-' + stamp + '.log')
log.parent.mkdir(exist_ok=True)
command = ([os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/c', str(root / 'gradlew.bat')]
           if os.name == 'nt' else [str(root / 'gradlew')])
command += ['-I', 'tools/verification/verify.init.gradle', 'runServer', '--no-daemon',
            '--console=plain', '-PauditRunDir=' + str(run_dir)]
if args.offline:
    command.append('--offline')
print('Portal audit world:', run_dir, flush=True)
print('Portal audit log:', log, flush=True)
with log.open('w', encoding='utf-8') as output:
    process = subprocess.Popen(command, cwd=root, stdout=output, stderr=subprocess.STDOUT,
                               start_new_session=os.name != 'nt')
    try:
        code = process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        if os.name == 'nt':
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
        else:
            import signal
            os.killpg(process.pid, signal.SIGKILL)
        process.wait()
        sys.exit('FAIL: audit timed out; see ' + str(log))
text = log.read_text(encoding='utf-8', errors='replace')
for line in text.splitlines():
    if '[ip-audit]' in line:
        print(line)
results = re.findall(r'\[ip-audit\] COMPLETE checks=(\d+) failures=(\d+)', text)
if code != 0 or len(results) != 1 or int(results[0][0]) == 0 or int(results[0][1]) != 0:
    print('\n'.join(text.splitlines()[-35:]))
    sys.exit('FAIL: audit did not complete successfully; see ' + str(log))
print('PASS: portal regression completed and server shut down successfully')
