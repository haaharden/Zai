import { build } from "esbuild";
import { mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const outArgIndex = process.argv.indexOf("--out");
const outDir =
  outArgIndex > 0 && process.argv[outArgIndex + 1]
    ? resolve(process.argv[outArgIndex + 1])
    : resolve("../app/src/main/assets/pi");

const piPkg = JSON.parse(
  readFileSync(resolve("node_modules/@earendil-works/pi-agent-core/package.json"), "utf8"),
);

mkdirSync(outDir, { recursive: true });

const codexWebCryptoPlugin = {
  name: "codex-webcrypto-random-bytes",
  setup(build) {
    build.onLoad({ filter: /[\\/]auth[\\/]oauth[\\/]openai-codex\.js$/ }, (args) => {
      const src = readFileSync(args.path, "utf8");
      const patched = src.replace(
        /let _randomBytes = null;[\s\S]*?\}\s*\n/,
        'import { randomBytes as _randomBytes } from "node:crypto";\n',
      );
      if (patched === src) {
        throw new Error("Failed to patch openai-codex.js randomBytes loader for WebView");
      }
      return { contents: patched, loader: "js" };
    });
  },
};

await build({
  entryPoints: ["src/host.ts"],
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "chrome105",
  minify: true,
  sourcemap: false,
  plugins: [codexWebCryptoPlugin],
  alias: {
    "node:http": "./src/shims/http.ts",
    "node:crypto": "./src/shims/crypto.ts",
    crypto: "./src/shims/crypto.ts",
    path: "./src/shims/node-stub.ts",
    fs: "./src/shims/node-stub.ts",
    module: "./src/shims/node-stub.ts",
    standardwebhooks: "./src/shims/standardwebhooks.ts",
  },
  define: {
    __PI_VERSION__: JSON.stringify(piPkg.version),
  },
  outfile: resolve(outDir, "host.js"),
});

writeFileSync(
  resolve(outDir, "index.html"),
  `<!doctype html><meta charset="utf-8"><title>zai-pi-host</title><script src="host.js"></script>\n`,
);

const sizeKb = (statSync(resolve(outDir, "host.js")).size / 1024).toFixed(0);
console.log(`Built Zai Pi host (${piPkg.version}) -> ${resolve(outDir, "host.js")} (${sizeKb} KiB)`);
