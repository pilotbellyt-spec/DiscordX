// Binary AndroidManifest.xml inspector & patcher.
// - Reads <manifest package, versionCode, versionName> and <application android:name>
// - Removes split-APK attributes (requiredSplitTypes, splitTypes, isSplitRequired) and flips
//   meta-data com.android.vending.splits.required=false when merging split APKs
// - Optionally renames package, provider authorities, package-scoped permissions, task affinities,
//   and application label for side-by-side installation next to stock Discord.

import { buildPool, decodeRaw, encodeRaw, parsePool } from './arsc.mjs';

const DROP_ATTRS = new Set([0x0101064e, 0x0101064f, 0x01010591]); // requiredSplitTypes, splitTypes, isSplitRequired
const ATTR = {
  label: 0x01010001,
  name: 0x01010003,
  taskAffinity: 0x01010012,
  authorities: 0x01010018,
  value: 0x01010024,
  versionCode: 0x0101021b,
  versionName: 0x0101021c,
};
const TYPE_REFERENCE = 0x01;
const TYPE_STRING = 0x03;

/**
 * Inspect binary AndroidManifest.xml without modifying it.
 * @returns {{packageName:string, applicationName:string|null, versionName:string|null, versionCode:number|null, isSplit:boolean}}
 */
export function inspectManifest(src) {
  const u8 = src instanceof Uint8Array ? src : new Uint8Array(src);
  const v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  if (v.getUint16(0, true) !== 0x0003) throw new Error('not a binary AndroidManifest.xml');

  let pool = null;
  let resIds = [];
  let packageName = null;
  let applicationName = null;
  let versionName = null;
  let versionCode = null;
  let isSplit = false;

  const str = (i) => (i === 0xffffffff || !pool ? null : decodeRaw(pool.raws[i], pool.utf8));

  for (let p = v.getUint16(2, true); p < u8.length; p += v.getUint32(p + 4, true)) {
    const type = v.getUint16(p, true);
    const size = v.getUint32(p + 4, true);
    if (type === 0x0001) {
      pool = parsePool(u8.subarray(p, p + size));
    } else if (type === 0x0180) {
      resIds = Array.from({ length: (size - 8) / 4 }, (_, i) => v.getUint32(p + 8 + 4 * i, true));
    } else if (type === 0x0102) {
      const aStart = p + 16 + v.getUint16(p + 24, true);
      const aSize = v.getUint16(p + 26, true);
      const aCount = v.getUint16(p + 28, true);
      const tag = str(v.getUint32(p + 20, true));
      for (let i = 0; i < aCount; i++) {
        const a = aStart + i * aSize;
        const nameIdx = v.getUint32(a + 4, true);
        const res = resIds[nameIdx];
        const vtype = u8[a + 15];
        const vdata = v.getUint32(a + 16, true);
        if (DROP_ATTRS.has(res)) isSplit = true;
        if (tag === 'manifest') {
          if (str(nameIdx) === 'package' && vtype === TYPE_STRING) packageName = str(vdata);
          else if (res === ATTR.versionName && vtype === TYPE_STRING) versionName = str(vdata);
          else if (res === ATTR.versionCode) versionCode = vdata;
        } else if (tag === 'application' && res === ATTR.name && vtype === TYPE_STRING) {
          applicationName = str(vdata);
        }
      }
    }
  }

  if (applicationName && applicationName.startsWith('.') && packageName) {
    applicationName = packageName + applicationName;
  } else if (applicationName && !applicationName.includes('.') && packageName) {
    applicationName = `${packageName}.${applicationName}`;
  }

  return { packageName, applicationName, versionName, versionCode, isSplit };
}

/**
 * Patch binary AndroidManifest.xml: strip split attributes, disable Play Core missing-splits
 * check, and optionally rename the package and/or launcher label.
 */
export function patchManifest(src, { packageName = null, label = null } = {}) {
  const u8 = new Uint8Array(src);
  const v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  if (v.getUint16(0, true) !== 0x0003) throw new Error('not a binary AndroidManifest.xml');

  const meta = inspectManifest(u8);
  const origPkg = meta.packageName;
  const targetPkg = packageName || origPkg;
  const renaming = Boolean(origPkg && targetPkg && targetPkg !== origPkg);
  const rewritePkg = (s) =>
    s === origPkg ? targetPkg : s.startsWith(origPkg + '.') ? targetPkg + s.slice(origPkg.length) : s;

  const chunks = [];
  let pool = null;
  let poolIdx = -1;
  let resIds = [];
  let appLabelRef = null;
  for (let p = v.getUint16(2, true); p < u8.length; p += v.getUint32(p + 4, true)) chunks.push(p);

  const str = (i) => (i === 0xffffffff ? null : decodeRaw(pool.raws[i], pool.utf8));
  const newStrings = new Map();
  const strIndex = (s) => {
    if (!newStrings.has(s)) newStrings.set(s, pool.raws.length + newStrings.size);
    return newStrings.get(s);
  };

  const edits = new Map(); // attrOffset -> new string index
  const drops = new Set(); // attrOffset
  const bools = new Set(); // attrOffset -> false
  const stats = { removed: 0, flipped: false, renamed: 0, origPkg, targetPkg };

  for (const [ci, p] of chunks.entries()) {
    const type = v.getUint16(p, true);
    const size = v.getUint32(p + 4, true);
    if (type === 0x0001) {
      pool = parsePool(u8.subarray(p, p + size));
      poolIdx = ci;
    } else if (type === 0x0180) {
      resIds = Array.from({ length: (size - 8) / 4 }, (_, i) => v.getUint32(p + 8 + 4 * i, true));
    } else if (type === 0x0102) {
      const aStart = p + 16 + v.getUint16(p + 24, true);
      const aSize = v.getUint16(p + 26, true);
      const aCount = v.getUint16(p + 28, true);
      const attrs = Array.from({ length: aCount }, (_, i) => aStart + i * aSize);
      const resOf = (a) => resIds[v.getUint32(a + 4, true)];
      const valStr = (a) => (u8[a + 15] === TYPE_STRING ? str(v.getUint32(a + 16, true)) : null);
      const tag = str(v.getUint32(p + 20, true));

      for (const a of attrs) if (DROP_ATTRS.has(resOf(a))) drops.add(a);

      if (tag === 'meta-data' && attrs.some((a) => resOf(a) === ATTR.name && valStr(a) === 'com.android.vending.splits.required')) {
        const val = attrs.find((a) => resOf(a) === ATTR.value);
        if (val !== undefined) {
          bools.add(val);
          stats.flipped = true;
        }
      }

      if (!renaming && !label) continue;
      for (const a of attrs) {
        const res = resOf(a);
        const s = valStr(a);
        const set = (ns) => {
          if (ns !== s) {
            edits.set(a, strIndex(ns));
            stats.renamed++;
          }
        };
        if (renaming && tag === 'manifest' && str(v.getUint32(a + 4, true)) === 'package' && s) {
          set(targetPkg);
        } else if (renaming && res === ATTR.authorities && s) {
          set(s.split(';').map(rewritePkg).join(';'));
        } else if (renaming && (tag === 'permission' || tag === 'uses-permission') && res === ATTR.name && s) {
          set(rewritePkg(s));
        } else if (renaming && res === ATTR.taskAffinity && s) {
          set(rewritePkg(s));
        } else if (label && res === ATTR.label) {
          const isRef = u8[a + 15] === TYPE_REFERENCE;
          const ref = v.getUint32(a + 16, true);
          if (tag === 'application' && isRef) appLabelRef = ref;
          if (isRef && (tag === 'application' || ref === appLabelRef)) {
            edits.set(a, strIndex(label));
            stats.renamed++;
          } else if (tag === 'application' && u8[a + 15] === TYPE_STRING && s !== label) {
            set(label);
          }
        }
      }
    }
  }
  if (!pool) throw new Error('AndroidManifest.xml has no string pool');

  // If nothing changed at all, return input untouched.
  if (!drops.size && !bools.size && !edits.size) {
    return { data: u8, ...stats };
  }

  for (const s of newStrings.keys()) pool.raws.push(encodeRaw(s, pool.utf8));

  const out = [u8.subarray(0, v.getUint16(2, true))];
  for (const [ci, p] of chunks.entries()) {
    const size = v.getUint32(p + 4, true);
    if (ci === poolIdx) {
      out.push(newStrings.size ? buildPool(pool) : u8.subarray(p, p + size));
      continue;
    }
    if (v.getUint16(p, true) !== 0x0102) {
      out.push(u8.subarray(p, p + size));
      continue;
    }
    const aStart = p + 16 + v.getUint16(p + 24, true);
    const aSize = v.getUint16(p + 26, true);
    const aCount = v.getUint16(p + 28, true);
    const attrs = Array.from({ length: aCount }, (_, i) => aStart + i * aSize);
    if (!attrs.some((a) => drops.has(a) || edits.has(a) || bools.has(a))) {
      out.push(u8.subarray(p, p + size));
      continue;
    }

    const keep = attrs.filter((a) => !drops.has(a));
    stats.removed += attrs.length - keep.length;
    const head = u8.slice(p, aStart);
    const hv = new DataView(head.buffer);
    const tail = u8.subarray(aStart + aCount * aSize, p + size);
    const n = head.length + keep.length * aSize + tail.length;
    hv.setUint32(4, n, true);
    hv.setUint16(28, keep.length, true);
    for (const off of [30, 32, 34]) {
      const idx = hv.getUint16(off, true);
      if (idx) hv.setUint16(off, keep.indexOf(attrs[idx - 1]) + 1, true);
    }

    const chunk = new Uint8Array(n);
    const cv = new DataView(chunk.buffer);
    chunk.set(head, 0);
    keep.forEach((a, i) => {
      const o = head.length + i * aSize;
      chunk.set(u8.subarray(a, a + aSize), o);
      if (edits.has(a)) {
        const si = edits.get(a);
        cv.setUint32(o + 8, si, true);
        cv.setUint16(o + 12, 8, true);
        cv.setUint8(o + 14, 0);
        cv.setUint8(o + 15, TYPE_STRING);
        cv.setUint32(o + 16, si, true);
      } else if (bools.has(a)) {
        cv.setUint32(o + 8, 0xffffffff, true);
        cv.setUint8(o + 15, 0x12);
        cv.setUint32(o + 16, 0, true);
      }
    });
    chunk.set(tail, head.length + keep.length * aSize);
    out.push(chunk);
  }

  let total = 0;
  for (const c of out) total += c.length;
  const res = new Uint8Array(total);
  let o = 0;
  for (const c of out) {
    res.set(c, o);
    o += c.length;
  }
  new DataView(res.buffer).setUint32(4, total, true);
  return { data: res, ...stats };
}


