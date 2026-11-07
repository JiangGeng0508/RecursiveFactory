"""Run regression probes in disposable worlds and enforce their completion result."""
import argparse
from datetime import datetime
import os
from pathlib import Path
import re
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def successful(code, output):
    results = re.findall(r"\[rfaudit\] COMPLETE checks=(\d+) failures=(\d+)", output)
    return (code == 0 and len(results) == 1 and int(results[0][0]) > 0
            and int(results[0][1]) == 0 and "[rfaudit] FAIL " not in output)


def run_process(command, log, timeout):
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=os.name != "nt",
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True,
                               creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return 124


def audit(args, without_power):
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    label = "without-cee" if without_power else "with-cee"
    run_dir = ROOT / "run" / "verification" / (label + "-" + stamp)
    run_dir.mkdir(parents=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text(
        'server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nview-distance=2\nsimulation-distance=2\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nlevel-seed=1\nspawn-protection=0\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n',
        encoding="utf-8")
    log = ROOT / "build" / "verification" / (label + "-" + stamp + ".log")
    log.parent.mkdir(parents=True, exist_ok=True)
    command = ([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(ROOT / "gradlew.bat")]
               if os.name == "nt" else [str(ROOT / "gradlew")])
    command += ["-I", "tools/verification/verify.init.gradle", "runServer", "--no-daemon",
                "--console=plain", "-PauditRunDir=" + str(run_dir)]
    if args.offline:
        command.append("--offline")
    if without_power:
        command.append("-PauditWithoutPower")
    print("Audit world:", run_dir, "\nAudit log:", log, flush=True)
    code = run_process(command, log, args.timeout)
    output = log.read_text(encoding="utf-8", errors="replace")
    for line in output.splitlines():
        if "[rfaudit]" in line:
            print(line)
    if not successful(code, output):
        print("\n".join(output.splitlines()[-40:]))
        print("FAIL:", "timeout" if code == 124 else "incomplete or failed regression", log)
        return False
    print("PASS:", label)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--without-power", action="store_true")
    parser.add_argument("--matrix", action="store_true", help="Run with and without CEE sequentially")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("timeout must be positive")
    return 0 if all(audit(args, mode) for mode in ([False, True] if args.matrix else [args.without_power])) else 1


if __name__ == "__main__":
    sys.exit(main())
