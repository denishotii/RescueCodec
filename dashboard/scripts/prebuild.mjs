// Removes the previous dashboard build from the gateway's static folder (only our own files).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const out = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../rescuecodec/gateway/static");
for (const name of ["assets", "index.html", "favicon.svg"]) fs.rmSync(path.join(out, name), { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
