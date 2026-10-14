import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const connector = fileURLToPath(
  new URL(
    "../java/endpoint-connector/build/install/endpoint-connector/bin/endpoint-connector",
    import.meta.url,
  ),
);
const result = spawnSync(connector, {
  env: process.env,
  stdio: "inherit",
});

if (result.error) {
  console.error("TracePermit demo: the endpoint client could not start.");
  process.exit(2);
}
process.exit(result.status ?? 2);
