#!/usr/bin/env python3
"""Build Buddy's auto-placing on a real vanilla dedicated server, off-screen.

mp.py [--mc 26.3] [--port 25960] [--keep-server-log]

A vanilla flat-world dedicated server (online-mode=false, spawn-protection=0, peaceful) and the Build Buddy client (Fabric
API, scene `server-auto`, -Dbuildbuddy.demo.server=true) which joins it as `Builder`. The mod has no integrated server
there, so the strict player-equivalent rules apply and the server key is the address (pre-seeded as allowed in
config/buildbuddy/servers.json, so the first-use disclaimer is skipped). This script is the server side: it ops the
player, fixes time and rules, and after each phase asks the server, from the console, whether the blocks really stand
there with the right facing (`execute if block`). The scene meets it through files in the client's out folder:
go -> (Sweep) sweep-done / verified-sweep -> (Assist) assist-done / verified-assist.
Checks: the client's own results.json (own world false, every block right, never above 20 a second, still connected), the
server's block checks, and the server log (no kick, no warning about the player, no error). Work dir: dev/server/.work/mp.
"""
import argparse
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
WS = ROOT.parents[1]
sys.path.insert(0, str(WS / "tools/ModTest"))
sys.path.insert(0, str(WS / "tools/ModJar"))
import client  # noqa: E402
import slots  # noqa: E402

_spec = importlib.util.spec_from_file_location("modjar_smoke", WS / "tools/ModJar" / "smoke.py")
ms = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ms)

USER = ("Builder", "5e1dcaa0-0000-4000-8000-00000000c001")
OX, OZ, Y0 = 30, 30, -60
LOG_ERRORS = "Build Buddy.*(cannot|failed|error)"


def jar() -> Path:
    version = re.search(r"^mod\.version=(.+)$", (ROOT / "gradle.properties").read_text(), re.M).group(1).strip()
    j = ROOT / "build/libs" / f"buildbuddy-{version}+26.3-fabric.jar"
    if not j.is_file():
        sys.exit(f"no {j.name} in {j.parent}: build it first (./gradlew build --offline)")
    return j


def vanilla_cmd(mc: str, work: Path) -> list[str]:
    meta = client.META
    vdir = sorted(meta.glob(f"versions/{mc}-*"))[-1]
    cp = subprocess.check_output(["python3", str(WS / "tools/ClientCapture/classpath.py"), str(vdir / f"{vdir.name}.json"), str(meta / "libraries")], text=True).strip()
    return [ms.java(), "-Xmx2G", f"-Djava.io.tmpdir={work / 'tmp'}", "-cp", cp + ":" + str(vdir / f"{vdir.name}.jar"), "net.minecraft.server.Main", "--nogui"]


class Server:
    def __init__(self, work: Path, cmd: list[str], port: int):
        shutil.rmtree(work, ignore_errors=True)
        (work / "tmp").mkdir(parents=True)
        (work / "eula.txt").write_text("eula=true\n")
        (work / "server.properties").write_text(
            f"online-mode=false\nserver-ip=127.0.0.1\nserver-port={port}\nlevel-type=minecraft\\:flat\ngenerate-structures=false\n"
            "view-distance=8\nsimulation-distance=8\nspawn-protection=0\ndifficulty=peaceful\ngamemode=survival\nspawn-monsters=false\nspawn-animals=false\n"
            "pause-when-empty-seconds=0\nwhite-list=false\nenforce-whitelist=false\nsync-chunk-writes=false\nallow-flight=false\n")
        self.log = work / "server.log"
        self.out = open(self.log, "w")
        self.proc = subprocess.Popen(cmd, cwd=work, stdin=subprocess.PIPE, stdout=self.out, stderr=subprocess.STDOUT, text=True)
        self.lock = threading.Lock()

    def send(self, *lines):
        with self.lock:
            for l in lines:
                self.proc.stdin.write(l + "\n")
            self.proc.stdin.flush()

    def text(self) -> str:
        return self.log.read_text(errors="replace")

    def wait(self, pattern: str, timeout: float, after: int = 0) -> re.Match | None:
        end = time.time() + timeout
        while time.time() < end:
            m = re.search(pattern, self.text()[after:])
            if m:
                return m
            if self.proc.poll() is not None:
                return None
            time.sleep(0.25)
        return None

    def test(self, cmd: str) -> bool:
        n = len(self.text())
        self.send(cmd)
        m = self.wait(r"Test (passed|failed)", 10, n)
        return bool(m and m.group(1) == "passed")

    def stop(self):
        try:
            self.send("stop")
            self.proc.wait(90)
        except Exception:
            self.proc.kill()
        self.out.close()


def wait_file(f: Path, timeout: float) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        if f.exists():
            return True
        time.sleep(0.25)
    return False


def expected() -> dict[tuple[int, int, int], str]:
    """Samples.autoTest(), cell by cell: block id with the properties that matter."""
    e: dict[tuple[int, int, int], str] = {}
    for x in range(5):
        for z in range(3):
            e[(x, 0, z)] = "stone_bricks"
    for y in (1, 2):
        for x in range(5):
            for z in (0, 2):
                e[(x, y, z)] = "oak_planks"
        for x, z in ((0, 0), (4, 0), (0, 2), (4, 2)):
            e[(x, y, z)] = "oak_log[axis=y]"
        e[(0, y, 1)] = e[(4, y, 1)] = "glass_pane"
    e[(2, 1, 0)] = "oak_door[facing=north,half=lower]"
    e[(2, 2, 0)] = "oak_door[facing=north,half=upper]"
    e[(1, 1, 1)] = "oak_slab[type=top]"
    e[(3, 1, 1)] = "oak_slab[type=bottom]"
    e[(1, 2, 1)] = "wall_torch[facing=south]"
    e[(2, 2, 1)] = "lantern[hanging=true]"
    for x in range(5):
        e[(x, 3, 0)] = "oak_stairs[facing=south,half=bottom]"
        e[(x, 3, 2)] = "oak_stairs[facing=north,half=bottom]"
    e[(0, 3, 1)] = "oak_stairs[facing=east,half=bottom]"
    e[(4, 3, 1)] = "oak_stairs[facing=west,half=bottom]"
    for x in (1, 2, 3):
        e[(x, 3, 1)] = "oak_slab[type=bottom]"
    return e


def check_blocks(srv: Server, what: str, cells: dict, notes: list[str]) -> bool:
    bad = []
    for (x, y, z), block in sorted(cells.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        if not srv.test(f"execute if block {OX + x} {Y0 + y} {OZ + z} minecraft:{block}"):
            bad.append(f"[{x},{y},{z} {block}]")
    ok = not bad
    notes.append(("PASS " if ok else "FAIL ") + f"server/{what}_blocks  " + (f"all {len(cells)} cells are on the server as built (stairs facing, door halves, slab types, torch, lantern)" if ok else f"{len(bad)} of {len(cells)} wrong on the server: " + " ".join(bad[:12])))
    return ok


def tick_query(srv: Server) -> str:
    n = len(srv.text())
    srv.send("tick query")
    m = srv.wait(r"Average time per tick: [\d.]+ms[^\n]*", 10, n)
    return m.group(0) if m else "no answer"


def run(mc: str, port: int) -> tuple[bool, list[str]]:
    work = HERE / ".work" / "mp"
    shutil.rmtree(work, ignore_errors=True)
    game, out = work / "game", work / "out"
    (game / "config/buildbuddy").mkdir(parents=True)
    (game / "config/buildbuddy/servers.json").write_text(json.dumps({"remembered": {f"127.0.0.1:{port}": "allowed"}, "blocked": []}))
    notes, ok = [], True
    allcells = expected()
    assist = {c: b for c, b in allcells.items() if c[1] == 0 or (c[2] == 0 and 1 <= c[1] <= 3 and not (c[0] == 2 and c[1] <= 2))}
    result: dict = {}
    with slots.slot("server", f"BuildBuddy mp {mc}"):
        srv = Server(work / "server", vanilla_cmd(mc, work / "server"), port)
        try:
            if not srv.wait(r"Done \(\d", 240):
                return False, ["server did not start"] + ms.tail(srv.log)

            def run_client():
                result["r"] = client.run(
                    mc=mc, loader="fabric", out=out, game=game, mod_jars=[jar()], fabric_api_jar=True, keep_out=True,
                    props={"buildbuddy.demo": str(out), "buildbuddy.demo.scenes": "server-auto", "buildbuddy.demo.server": "true", "modtest.audit": "1"},
                    join=f"127.0.0.1:{port}", options={"fps": 60, "volume": 0.0001, "render_distance": 8, "xmx": "3G"}, width=960, height=540,
                    timeout=1500, user=USER[0], uuid=USER[1], log_errors=LOG_ERRORS, label="BuildBuddy mp server-auto")

            out.mkdir(parents=True, exist_ok=True)
            t = threading.Thread(target=run_client)
            t.start()
            if not srv.wait(r"Builder joined the game", 400):
                notes.append("FAIL the client never joined")
                ok = False
            else:
                time.sleep(2)
                srv.send("op Builder", "gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_monsters false",
                         "gamerule spawn_mobs false", "gamerule send_command_feedback false", "time set 6000", "weather clear")
                time.sleep(1)
                (out / "go").write_text("go")
                if wait_file(out / "sweep-done", 900):
                    notes.append(f"     server tick after sweep: {tick_query(srv)}")
                    ok &= check_blocks(srv, "sweep", allcells, notes)
                    (out / "verified-sweep").write_text("ok")
                    if wait_file(out / "assist-done", 900):
                        notes.append(f"     server tick after assist: {tick_query(srv)}")
                        ok &= check_blocks(srv, "assist", assist, notes)
                        (out / "verified-assist").write_text("ok")
                    else:
                        notes.append("FAIL the client never finished Assist")
                        ok = False
                else:
                    notes.append("FAIL the client never finished Sweep")
                    ok = False
            t.join(900)
        finally:
            srv.stop()
    r = result.get("r")
    if not r:
        return False, notes + ["FAIL the client did not report"]
    for x in r["results"]:
        notes.append(("PASS " if x["pass"] else "FAIL ") + f"{x['name']}  {x.get('detail', '')}")
        ok &= bool(x["pass"])
    if not r["results"]:
        ok = False
        notes.append(f"FAIL client: no results ({'; '.join(r['notes'])})")
    for n in r["notes"]:
        if n.startswith("log errors"):
            ok = False
            notes.append(f"FAIL client: {n[:300]}")
    clog = Path(r["log"]).read_text(errors="replace") if Path(r["log"]).exists() else ""
    sent = [l for l in clog.splitlines() if re.search(r"Disconnected|Connection (lost|reset)|Timed out|Kicked|kicked", l)]
    notes.append(("PASS " if not sent else "FAIL ") + "client/log_no_disconnect  " + (sent[0][:200] if sent else "no disconnect line in the client log"))
    ok &= not sent
    stext = srv.text()
    for who, why in re.findall(r"(\w+) lost connection: (.*)", stext):
        clean = "Disconnected" in why or "Server closed" in why
        notes.append(("PASS " if clean else "FAIL ") + f"server/{who}_left  {why[:200]}")
        ok &= clean
    kicks = [l for l in stext.splitlines() if re.search(r"Kicked|kicked|Disconnecting|moved wrongly|moved too quickly|Invalid|invalid|Can't keep up|Packet", l)]
    notes.append(("PASS " if not kicks else "FAIL ") + "server/no_kick_or_warning  " + (kicks[0].strip()[:220] if kicks else "no kick, no moved-wrongly/too-quickly, no 'Can't keep up', no packet complaint"))
    ok &= not kicks
    problems = [l for l in stext.splitlines() if ms.PROBLEM.search(l) and not any(re.search(n, l) for n in ms.NOISE)]
    notes.append(("PASS " if not problems else "FAIL ") + "server/log_clean  " + (f"{len(problems)} problem lines, first: {problems[0].strip()[:220]}" if problems else "no WARN/ERROR/exception line in the server log"))
    ok &= not problems
    for l in problems[:6]:
        notes.append("     SERVER LOG " + l.strip()[:220])
    return ok, notes


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", default="26.3")
    ap.add_argument("--port", type=int, default=25960)
    a = ap.parse_args()
    ok, notes = run(a.mc, a.port)
    total = passed = 0
    print(f"{'PASS' if ok else 'FAIL'} build buddy server-auto {a.mc}-fabric", flush=True)
    for n in notes:
        print("     " + n, flush=True)
        if n.startswith(("PASS", "FAIL")):
            total += 1
            passed += n.startswith("PASS")
    print(f"mp {a.mc}-fabric: {passed}/{total} passed, {'PASS' if ok else 'FAIL'}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
