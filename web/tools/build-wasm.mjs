import wabtFactory from 'wabt';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
const root = new URL('../', import.meta.url);
const wabt = await wabtFactory();
const module = wabt.parseWat('patch.wat', await readFile(new URL('patch.wat', root), 'utf8'));
module.validate();
await mkdir(new URL('public/lib/', root), {recursive:true});
await writeFile(new URL('public/patch.wasm', root), module.toBinary({}).buffer);
console.log('Built WASM patch engine.');
