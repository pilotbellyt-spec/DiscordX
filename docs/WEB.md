# DiscordX web builder

Cloudflare Workers serves `public/` and proxies the Vendetta tracker on the same origin. APK processing runs in a browser worker. A small WebAssembly module applies guarded native edits. JavaScript applies the host DEX delta to the user's original APK, merges ZIP entries, and signs with WebCrypto. This is a hybrid WASM builder, not an Android compiler port.

Only original Discord **356207**, **hdpi**, and **arm64-v8a or x86_64** are supported. The base DEX is checked by SHA-256 against the source used to prepare the DiscordX payload. The builder merges splits into one installable APK. No VMware workaround is included.

Prepare from the original base and a DiscordX base produced by `src/build.py`:

```powershell
python -m pip install -r web/tools/requirements.txt
python web/tools/prepare.py <path-to-original-base.apk> src/dist/phoenix-cinder-signed-356207/base.apk
cd web
npm ci
npm run build
npm run dev
```

Open the local URL Wrangler prints. Deploy with `npm run deploy` after signing into Cloudflare with `npx wrangler login`. Wrangler serves the static page and `/tracker/*` in one deployment. No Cloudflare credentials are embedded in browser assets.

The generated `public/signing-key.json` is an **intentionally public development identity**, separate from the VM's existing keystore. Anyone can sign updates with it. Keep this identity stable for web builds to update one another. Never replace it with a private production signing key. A web-built APK cannot update the current VM installation or Play Store Discord because their signers differ. Switching installations can remove app data; the builder never uninstalls anything.

Automatic downloads use the tracker through the deployed worker. Local mode accepts the original three splits when the tracker is unavailable. Files are processed locally; APKs and signing operations are never uploaded to the worker.

`public/payload/host.patch` contains a binary delta, not a complete Discord DEX. Preparation generates BSDIFF copy/add/seek controls, byte differences, and added bytes, then gzip-compresses them in the DXD2 format. The browser reconstructs the hooked DEX from the original and verifies its SHA-256 before packaging. The manifest pins both source and output hashes. The complete host DEX stays in local build artifacts and must not be committed or published as a website asset. The bundled bootstrap and module DEX files contain DiscordX code.

Vendor modules derive from [Cinder](https://github.com/mufarodev/cinder) (`zip`, `sign`, `axml`, `arsc`) and [phoenix-unlock](https://github.com/mantikafasi/phoenix-unlock) (arm64 access finder). Only the Phoenix access finder is invoked; staff hooks and their plugins are not included. Preserve upstream attribution when distributing.
