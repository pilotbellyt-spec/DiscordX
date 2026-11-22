import {readFileSync, writeFileSync} from 'node:fs';
import {accessEdit} from './public/lib/arm64.mjs';
const bytes = new Uint8Array(readFileSync(process.argv[2]));
const edit = accessEdit(bytes);
bytes.set(edit.patched, edit.offset);
console.log(`Phoenix access evaluator at 0x${edit.offset.toString(16)}`);
writeFileSync(process.argv[3], bytes);
