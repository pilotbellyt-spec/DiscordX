import {readFileSync, writeFileSync} from 'node:fs';
import {accessEdit, devToolsEdit} from './public/lib/arm64.mjs';
const bytes = new Uint8Array(readFileSync(process.argv[2]));
for (const [name, edit] of [['access evaluator', accessEdit(bytes)], ['DevTools visibility gate', devToolsEdit(bytes)]]) {
  if (edit.original.some((value, index) => bytes[edit.offset + index] !== value)) throw new Error(`Unexpected ${name} bytes`);
  bytes.set(edit.patched, edit.offset);
  console.log(`Phoenix ${name} at 0x${edit.offset.toString(16)}`);
}
writeFileSync(process.argv[3], bytes);
