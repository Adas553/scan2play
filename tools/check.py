"""The project's checks in a copy of the repo, each answered in a few lines (CLAUDE.md: never run Maven inside the repo).

    python tools/check.py unit              the unit tests ("-Dtest=!*IT")
    python tools/check.py unit -t FooTest   one class (or a pattern, as -Dtest takes it)
    python tools/check.py it                the *IT tests on a throw-away PostgreSQL database (mvn verify -Pit)
    python tools/check.py browser           the browser scenarios on the pages the unit tests rendered (run.py --no-render)
    python tools/check.py all               unit, then it, then browser — stops at the first that fails

What it prints: the totals, then only what failed (the test, the first lines of its message) — never Maven's whole log, which is
kept in <work>-logs/<step>.log for when the lines are not enough. Exit status 0 only when everything passed.

It copies the repo without target/, .git/, .idea/ into the work directory (default: <temp>/scan2play-check), and deletes the copy's
target/classes and target/test-classes before each run: a copy keeps the old file times, so Maven would keep stale classes (a fix that
"does nothing" or a mutation that survives — seen twice). Maven: `mvn` when it is on the PATH, else the copy's mvnw / mvnw.cmd.
For the ITs: PGHOST / PGPORT / PGUSER / PGPASSWORD (default localhost, 5432, postgres, 1111 — the local profile); a refused connection
is said as such, not as 152 failed tests. Standard library only.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SKIP = {"target", ".git", ".idea", "node_modules", "__pycache__"}


def copy(work: Path) -> None:
    """The repo mirrored into work: changed files copied, files gone from the repo removed, target/ kept but for its classes."""
    work.mkdir(parents=True, exist_ok=True)
    for root, dirs, files in os.walk(REPO):
        rel = Path(root).relative_to(REPO)
        dirs[:] = [d for d in dirs if d not in SKIP]
        (work / rel).mkdir(parents=True, exist_ok=True)
        for name in files:
            src, dst = Path(root) / name, work / rel / name
            if not dst.exists() or src.stat().st_mtime_ns != dst.stat().st_mtime_ns or src.stat().st_size != dst.stat().st_size:
                shutil.copy2(src, dst)
    for root, dirs, files in os.walk(work, topdown=True):
        rel = Path(root).relative_to(work)
        dirs[:] = [d for d in dirs if d not in SKIP]
        for name in files:
            if not (REPO / rel / name).exists():
                (Path(root) / name).unlink()
    for stale in ("classes", "test-classes"):
        shutil.rmtree(work / "target" / stale, ignore_errors=True)


def logs(work: Path) -> Path:
    """Beside the copy, not in it: run.py mirrors the repo into the copy and removes what the repo does not have."""
    folder = work.parent / (work.name + "-logs")
    folder.mkdir(parents=True, exist_ok=True)
    return folder


def maven(work: Path) -> list:
    if shutil.which("mvn"):
        return ["mvn"]
    wrapper = work / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    return [str(wrapper)]


def run(work: Path, step: str, args: list, env=None) -> tuple:
    log = logs(work) / f"{step}.log"
    with open(log, "w", encoding="utf-8", errors="replace") as out:
        code = subprocess.call(args, cwd=work, stdout=out, stderr=subprocess.STDOUT, env=env, shell=(os.name == "nt"))
    return code, log.read_text(encoding="utf-8", errors="replace"), log


def summary_maven(step: str, code: int, text: str, log: Path) -> bool:
    totals = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", text, re.M)
    if "COMPILATION ERROR" in text:
        print(f"{step}: COMPILATION ERROR")
        for line in re.findall(r"^\[ERROR\] (/.*?\.java:\[\d+,\d+\] .*)$", text, re.M)[:10]:
            print("   ", line[-220:])
        print(f"    log: {log}")
        return False
    if not totals:
        print(f"{step}: no test totals (exit {code}) — the end of the log:")
        for line in text.strip().splitlines()[-15:]:
            print("   ", line[:220])
        print(f"    log: {log}")
        return False
    run_, fail, err, skip = map(int, totals[-1])
    ok = code == 0 and fail == 0 and err == 0
    print(f"{step}: {run_} tests, {fail} failed, {err} errors, {skip} skipped — {'OK' if ok else 'FAILED'}")
    if not ok:
        if "Connection to localhost" in text and "refused" in text:
            print("    PostgreSQL refused the connection — is it running? (cloud: pg_ctlcluster 16 main start)")
        failed = re.findall(r"^\[ERROR\] (\S+?) -- Time elapsed.*?<<< (?:FAILURE|ERROR)!\n(.*?)(?=\n\tat |\n\n|\n\[(?:INFO|ERROR|WARNING)\])", text, re.M | re.S)
        seen = set()
        for name, message in failed:
            if name in seen:
                continue
            seen.add(name)
            print(f"  x {name}")
            for line in message.strip().splitlines()[:4]:
                print("      ", line[:220])
            if len(seen) >= 15:
                print(f"  … more in {log}")
                break
        print(f"    log: {log}")
    return ok


def unit(work: Path, only: str) -> bool:
    pattern = only or "!*IT"
    code, text, log = run(work, "unit", maven(work) + ["-B", "-ntp", "test", f"-Dtest={pattern}", "-Dsurefire.failIfNoSpecifiedTests=false"])
    return summary_maven("unit", code, text, log)


def it(work: Path, only: str) -> bool:
    env = dict(os.environ)
    for key, default in (("PGHOST", "localhost"), ("PGPORT", "5432"), ("PGUSER", "postgres"), ("PGPASSWORD", "1111")):
        env.setdefault(key, default)
    args = maven(work) + ["-B", "-ntp", "verify", "-Pit"]
    if only:
        args.append(f"-Dit.test={only}")
    code, text, log = run(work, "it", args, env)
    return summary_maven("it", code, text, log)


def browser(work: Path, only: str) -> bool:
    if not (work / "target" / "browser-harness" / "dashboard.html").exists():
        print("browser: no rendered pages in the copy — run `unit` first")
        return False
    args = [sys.executable, str(REPO / "src" / "test" / "browser" / "run.py"), "--no-render", "--work", str(work)]
    if os.environ.get("S2P_CHROME"):
        args += ["--chrome", os.environ["S2P_CHROME"], "--no-sandbox"]
    if only:
        args += only.split(",")
    log = logs(work) / "browser.log"
    with open(log, "w", encoding="utf-8", errors="replace") as out:
        code = subprocess.call(args, cwd=REPO, stdout=out, stderr=subprocess.STDOUT)
    text = log.read_text(encoding="utf-8", errors="replace")
    last = [l for l in text.splitlines() if "scenario(s) in" in l]
    failed = re.findall(r"^FAIL\s+(\S+).*?\n((?:      .*\n){0,6})", text, re.M)
    print(f"browser: {last[-1].strip() if last else 'no total'} — {'OK' if code == 0 else 'FAILED'}")
    for name, lines in failed[:15]:
        print(f"  x {name}")
        for line in lines.rstrip().splitlines():
            print("   ", line.strip()[:220])
    if code != 0:
        print(f"    log: {log}")
    return code == 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=["unit", "it", "browser", "all"])
    parser.add_argument("-t", "--only", default="", help="unit / it: a test class or pattern; browser: scenario names, comma-separated")
    parser.add_argument("--work", default=os.path.join(tempfile.gettempdir(), "scan2play-check"))
    options = parser.parse_args()
    work = Path(options.work).resolve()
    if REPO in work.parents or work == REPO:
        print("the work directory must not be inside the repo")
        return 2
    copy(work)
    steps = {"unit": unit, "it": it, "browser": browser}
    for step in (["unit", "it", "browser"] if options.step == "all" else [options.step]):
        if not steps[step](work, options.only):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
