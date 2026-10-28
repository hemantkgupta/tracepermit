import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { once } from "node:events";
import test from "node:test";

const run = promisify(execFile);
const reaper = fileURLToPath(
  new URL("./owned-test-reaper.pl", import.meta.url),
);
const options = { timeout: 10_000, maxBuffer: 4_096 };

if (process.platform === "linux") {
  test("preserves command streams and a nonzero exit", async () => {
    await assert.rejects(
      run(
        "perl",
        [
          reaper,
          "python3",
          "-c",
          "import sys; print('OUT'); print('ERR',file=sys.stderr); sys.exit(37)",
        ],
        options,
      ),
      (error) =>
        error.code === 37 &&
        error.stdout === "OUT\n" &&
        error.stderr === "ERR\n",
    );
  });

  test("reaps an orphan while its command is still alive", async () => {
    const code = `
import os,signal,time
r,w=os.pipe()
parent=os.fork()
if parent==0:
    orphan=os.fork()
    if orphan==0:
        os.close(r);os.close(w);time.sleep(60);os._exit(0)
    os.write(w,str(orphan).encode());os._exit(0)
os.close(w)
orphan=int(os.read(r,64));os.close(r);os.waitpid(parent,0)
os.kill(orphan,signal.SIGKILL)
deadline=time.monotonic()+3
while os.path.exists('/proc/'+str(orphan)) and time.monotonic()<deadline: time.sleep(.01)
assert not os.path.exists('/proc/'+str(orphan)), 'ORPHAN_NOT_REAPED'
print('REAPED')
`;
    const result = await run("perl", [reaper, "python3", "-c", code], options);
    assert.equal(result.stdout, "REAPED\n");
    assert.equal(result.stderr, "");
  });

  test("leaked TERM-resistant descendants fail cleanup without killing an unrelated process", async () => {
    const unrelated = spawn("python3", ["-c", "import time;time.sleep(60)"], {
      stdio: "ignore",
    });
    const unrelatedExit = new Promise((resolve) =>
      unrelated.once("exit", resolve),
    );
    let orphan;
    try {
      const code = `
import os,signal,time
r,w=os.pipe()
child=os.fork()
if child==0:
    os.close(r);signal.signal(signal.SIGTERM,signal.SIG_IGN);os.write(w,b'R');os.close(w)
    time.sleep(60);os._exit(0)
os.close(w);assert os.read(r,1)==b'R';os.close(r)
print(child,flush=True);os._exit(0)
`;
      await assert.rejects(
        run("perl", [reaper, "python3", "-c", code], options),
        (error) => {
          orphan = Number(error.stdout.trim());
          return (
            error.code === 79 &&
            /^\d+\n$/.test(error.stdout) &&
            error.stderr === "OWNED_TEST_REAPER_LEAKED_DESCENDANT\n"
          );
        },
      );
      assert.equal(existsSync(`/proc/${orphan}`), false);
      assert.equal(unrelated.exitCode, null);
      assert.equal(process.kill(unrelated.pid, 0), true);
    } finally {
      unrelated.kill("SIGKILL");
      await unrelatedExit;
    }
  });

  test("interruption reaps a TERM-resistant command within the cleanup reserve", async () => {
    const child = spawn("perl", [
      reaper,
      "python3",
      "-c",
      "import os,signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print(os.getpid(),flush=True);time.sleep(60)",
    ]);
    const closed = once(child, "close");
    let root;
    const timer = setTimeout(() => child.kill("SIGKILL"), 7_000);
    try {
      const [bytes] = await once(child.stdout, "data");
      assert.match(bytes.toString(), /^\d+\n$/);
      root = Number(bytes.toString().trim());
      child.kill("SIGTERM");
      const [exit, signal] = await closed;
      assert.equal(signal, null);
      assert.equal(exit, 137);
      assert.equal(existsSync(`/proc/${root}`), false);
    } finally {
      clearTimeout(timer);
      if (child.exitCode === null) child.kill("SIGTERM");
      await closed;
    }
  });
} else {
  test("rejects unsupported platforms without starting the command", async () => {
    await assert.rejects(
      run("perl", [reaper, "python3", "-c", "print('MUST_NOT_RUN')"], options),
      (error) =>
        error.code === 78 &&
        error.stdout === "" &&
        error.stderr === "OWNED_TEST_REAPER_UNAVAILABLE\n",
    );
  });
}
