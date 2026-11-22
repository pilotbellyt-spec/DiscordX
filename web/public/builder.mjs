import {readZip, entryData, storedEntry, deflatedEntry, writeZip} from './lib/zip.mjs';
import {signV2, decodeIdentity} from './lib/sign.mjs';
import {inspectManifest, patchManifest} from './lib/axml.mjs';
import {mergeArsc} from './lib/arsc.mjs';
import {accessEdit} from './lib/arm64.mjs';
import {applyDexPatch} from './lib/dex-patch.mjs';

export const sha256 = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b=>b.toString(16).padStart(2,'0')).join('');
const skip = name => /^META-INF\/[^/]+\.(MF|SF|RSA|DSA|EC)$/i.test(name) || name === 'stamp-cert-sha256';
export async function build({base, abi, density, architecture, payload, identity, wasm, log = ()=>{}}) {
  const baseEntries = readZip(base).filter(e=>!skip(e.name));
  const abiEntries = readZip(abi).filter(e=>!skip(e.name));
  const config = readZip(density).filter(e=>!skip(e.name));
  const extract = async (entries, name) => {
    const entry = entries.find(e=>e.name===name);
    if (!entry) throw new Error(`Missing ${name}`);
    return entryData(entry);
  };
  const manifest = await extract(baseEntries, 'AndroidManifest.xml');
  const info = inspectManifest(manifest);
  if (Number(info.versionCode) !== 356207 || info.packageName !== 'com.discord') throw new Error('Use unmodified Discord build 356207 splits.');
  if (baseEntries.some(e=>e.name==='classes4.dex' || e.name.startsWith('assets/discordx/'))) throw new Error('This APK already contains DiscordX. Use the original APK.');
  const originalDex = await extract(baseEntries, 'classes.dex');
  if (await sha256(originalDex) !== payload.manifest.sourceDexSha256) throw new Error('The base DEX differs from the supported build. Use the original 356207 base APK.');
  for (const [file, hash] of Object.entries(payload.manifest.files)) if (await sha256(payload[file]) !== hash) throw new Error(`Payload integrity check failed: ${file}`);
  log('Validated Discord build and DiscordX payloads.');
  const engine = (await WebAssembly.instantiate(wasm)).instance.exports;
  const allocate = size => {
    const pages = Math.ceil(size / 65536);
    if (engine.memory.buffer.byteLength < size) engine.memory.grow(pages - engine.memory.buffer.byteLength / 65536);
    return new Uint8Array(engine.memory.buffer);
  };
  const hostPath = `lib/${architecture}/liblibdiscord_wasm_host.so`;
  const native = await extract(abiEntries, hostPath);
  const machine = new DataView(native.buffer,native.byteOffset,native.byteLength).getUint16(18,true);
  if (machine !== (architecture==='x86_64' ? 62 : 183)) throw new Error('The selected CPU does not match the native APK.');
  let edit;
  if (architecture === 'arm64-v8a') edit = accessEdit(native);
  else {
    const offset = 0x2eac1c4;
    const expected = new Uint8Array([0x55,0x41,0x57,0x41,0x56,0x41,0x55,0x41,0x54,0x53,0x48,0x83,0xec,0x28,0xb3,2]);
    if (expected.some((b,i)=>native[offset+i]!==b)) throw new Error('Unexpected x86_64 access evaluator.');
    edit = {offset,original:expected.slice(0,3),patched:new Uint8Array([0xb0,1,0xc3])};
  }
  const size = native.length, count = edit.original.length;
  const memory = allocate(size + count*2);
  memory.set(native); memory.set(edit.original,size); memory.set(edit.patched,size+count);
  if (!engine.patch(edit.offset,size,size+count,count)) throw new Error('WASM native byte guard failed.');
  const patchedNative = memory.slice(0,size);
  log(`WASM patched ${architecture} Phoenix access gate at 0x${edit.offset.toString(16)}.`);
  const patchedDex = await applyDexPatch(originalDex, payload['host.patch']);
  if (await sha256(patchedDex) !== payload.manifest.hostDexSha256) throw new Error('Patched host DEX integrity check failed.');
  log('Applied DiscordX hooks to the original DEX.');
  const out = [], names = new Set(baseEntries.map(e=>e.name));
  for (const entry of baseEntries) {
    if (entry.name==='AndroidManifest.xml') out.push(storedEntry(entry.name,patchManifest(manifest,{}).data));
    else if (entry.name==='resources.arsc') out.push(storedEntry(entry.name,mergeArsc(await entryData(entry),[await extract(config,'resources.arsc')],null)));
    else if (entry.name==='classes.dex') out.push(await deflatedEntry(entry.name,patchedDex));
    else out.push(entry);
  }
  for (const entry of abiEntries) if (entry.name.startsWith('lib/') && !names.has(entry.name)) {
    out.push(entry.name===hostPath ? storedEntry(entry.name,patchedNative) : entry); names.add(entry.name);
  }
  for (const entry of config) if (entry.name.startsWith('res/') && !names.has(entry.name)) { out.push(entry); names.add(entry.name); }
  out.push(await deflatedEntry('classes4.dex',payload['bootstrap.dex']));
  out.push(storedEntry('assets/discordx/module.dex',payload['module.dex']));
  for (const file of Object.keys(payload.manifest.files)) if (file.startsWith('plugins/')) {
    out.push(storedEntry(`assets/discordx/${file}`, payload[file]));
  }
  log('Merged splits and injected DiscordX loader and plugins. Signing APK…');
  const parts = await signV2(writeZip(out),decodeIdentity(identity),log);
  log('Build complete.');
  return parts;
}
