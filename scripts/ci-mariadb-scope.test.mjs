import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const javaRoot = fileURLToPath(new URL("../java/", import.meta.url));

function sourceFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (["build", ".gradle"].includes(entry.name)) return [];
      return sourceFiles(path);
    }
    return entry.isFile() &&
      path.includes("/src/test/") &&
      path.endsWith(".java")
      ? [path]
      : [];
  });
}

test("all retained MariaDB 10 container fixtures are excluded and have MariaDB 11 counterparts", () => {
  const files = sourceFiles(javaRoot);
  const retired = [];
  for (const file of files) {
    const source = readFileSync(file, "utf8");
    const excluded = /Maria.*10.*Test\.java$/.test(basename(file));
    const startsMaria10 = /mariadb:10\./.test(source);
    if (startsMaria10) {
      assert.ok(excluded, `MariaDB 10 fixture is not excluded: ${file}`);
      retired.push(file);
    }
    if (excluded) {
      assert.ok(startsMaria10, `Exclusion would hide a non-10.x test: ${file}`);
      const counterpart = file.replace(
        /Maria(Db)?106/,
        (_, databasePrefix = "") => `Maria${databasePrefix}114`,
      );
      assert.notEqual(counterpart, file);
      assert.ok(
        existsSync(counterpart),
        `Missing MariaDB 11 counterpart: ${file}`,
      );
      assert.match(readFileSync(counterpart, "utf8"), /mariadb:11\.4\.13/);
    }
  }
  assert.ok(retired.length > 0, "Expected the retained historical fixtures");
});

test("parameterized MariaDB and replay fixtures retain 11.4 without starting 10.x", () => {
  const consumption = readFileSync(
    join(
      javaRoot,
      "persistence-mariadb/src/test/java/io/tracepermit/persistence/mariadb/MariaDbPermitConsumptionStoreTest.java",
    ),
    "utf8",
  );
  const replay = readFileSync(
    join(
      javaRoot,
      "persistence-identity/src/test/java/io/tracepermit/persistence/identity/ReplayRepairAcknowledgementTest.java",
    ),
    "utf8",
  );
  assert.match(consumption, /mariadb:11\.4\.13/);
  assert.doesNotMatch(consumption, /mariadb:10\./);
  assert.match(replay, /POSTGRES_16_4/);
  assert.match(replay, /MARIA_11_4_13/);
  assert.doesNotMatch(replay, /MARIA_10|mariadb:10\./);
});
