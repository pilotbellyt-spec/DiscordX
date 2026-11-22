// Binary Android resources.arsc string-pool codec, split-config merger, and package renamer.

const TYPE_STRING = 0x03;
const TYPE_SPARSE = 0x01;
const TYPE_OFFSET16 = 0x02;
const FLAG_COMPLEX = 0x0001;
const FLAG_COMPACT = 0x0008;

const tdUtf8 = new TextDecoder();
const tdUtf16 = new TextDecoder('utf-16le');
const teUtf8 = new TextEncoder();

const view = (u8) => new DataView(u8.buffer, u8.byteOffset, u8.byteLength);

export function parsePool(u8) {
  const v = view(u8);
  if (v.getUint16(0, true) !== 0x0001) throw new Error('expected ResStringPool chunk (0x0001)');
  const count = v.getUint32(8, true);
  const styles = v.getUint32(12, true);
  const flags = v.getUint32(16, true);
  const sStart = v.getUint32(20, true);
  const stStart = v.getUint32(24, true);
  const utf8 = Boolean(flags & 0x100);
  const raws = new Array(count);

  for (let i = 0; i < count; i++) {
    const o = sStart + v.getUint32(28 + 4 * i, true);
    let q = o;
    if (utf8) {
      q += u8[q] & 0x80 ? 2 : 1;
      let n = u8[q++];
      if (n & 0x80) n = ((n & 0x7f) << 8) | u8[q++];
      raws[i] = u8.subarray(o, q + n + 1);
    } else {
      let n = v.getUint16(q, true);
      q += 2;
      if (n & 0x8000) {
        n = ((n & 0x7fff) << 16) | v.getUint16(q, true);
        q += 2;
      }
      raws[i] = u8.subarray(o, q + 2 * n + 2);
    }
  }

  const styleOffs = u8.subarray(28 + 4 * count, 28 + 4 * count + 4 * styles);
  const styleData = styles ? u8.subarray(stStart) : new Uint8Array(0);
  return { raws, styles, flags, utf8, styleOffs, styleData };
}

export function decodeRaw(raw, utf8) {
  if (utf8) {
    let q = raw[0] & 0x80 ? 2 : 1;
    let n = raw[q++];
    if (n & 0x80) n = ((n & 0x7f) << 8) | raw[q++];
    return tdUtf8.decode(raw.subarray(q, q + n));
  }
  const v = view(raw);
  let n = v.getUint16(0, true);
  let q = 2;
  if (n & 0x8000) {
    n = ((n & 0x7fff) << 16) | v.getUint16(2, true);
    q = 4;
  }
  return tdUtf16.decode(raw.subarray(q, q + 2 * n));
}

export function encodeRaw(str, utf8) {
  if (utf8) {
    const b = teUtf8.encode(str);
    const encLen = (n) => (n > 0x7f ? [0x80 | (n >> 8), n & 0xff] : [n]);
    return Uint8Array.from([...encLen(str.length), ...encLen(b.length), ...b, 0]);
  }
  const n = str.length;
  const head = n > 0x7fff ? [0x8000 | (n >> 16), n & 0xffff] : [n];
  const out = new Uint8Array(2 * head.length + 2 * n + 2);
  const v = view(out);
  head.forEach((h, i) => v.setUint16(2 * i, h, true));
  for (let i = 0; i < n; i++) v.setUint16(2 * head.length + 2 * i, str.charCodeAt(i), true);
  return out;
}

export function buildPool(p) {
  const n = p.raws.length;
  let dataLen = 0;
  for (const r of p.raws) dataLen += r.length;
  const pad = (4 - (dataLen % 4)) % 4;
  const sStart = 28 + 4 * n + 4 * p.styles;
  const stStart = p.styles ? sStart + dataLen + pad : 0;
  const size = sStart + dataLen + pad + p.styleData.length;
  const out = new Uint8Array(size);
  const v = view(out);
  v.setUint16(0, 0x0001, true);
  v.setUint16(2, 28, true);
  v.setUint32(4, size, true);
  v.setUint32(8, n, true);
  v.setUint32(12, p.styles, true);
  v.setUint32(16, p.flags & ~0x1, true); // clear SORTED flag after appending
  v.setUint32(20, sStart, true);
  v.setUint32(24, stStart, true);
  let o = 0;
  for (let i = 0; i < n; i++) {
    v.setUint32(28 + 4 * i, o, true);
    out.set(p.raws[i], sStart + o);
    o += p.raws[i].length;
  }
  out.set(p.styleOffs, 28 + 4 * n);
  if (p.styles) out.set(p.styleData, stStart);
  return out;
}

function parseTable(u8) {
  const v = view(u8);
  if (v.getUint16(0, true) !== 0x0002) throw new Error('not a valid resources.arsc table');
  let pool = null;
  const pkgs = [];
  for (let p = v.getUint16(2, true); p < u8.length; p += v.getUint32(p + 4, true)) {
    const t = v.getUint16(p, true);
    const c = u8.subarray(p, p + v.getUint32(p + 4, true));
    if (t === 0x0001) pool = c;
    else if (t === 0x0200) pkgs.push(c);
  }
  if (pkgs.length !== 1) throw new Error(`expected 1 resource package, got ${pkgs.length}`);
  return { pool, pkg: parsePackage(pkgs[0]) };
}

function parsePackage(u8) {
  const v = view(u8);
  const hsz = v.getUint16(2, true);
  const typeStr = v.getUint32(268, true);
  const keyStr = v.getUint32(276, true);
  const header = u8.subarray(0, hsz);
  const types = u8.subarray(typeStr, typeStr + v.getUint32(typeStr + 4, true));
  const keys = u8.subarray(keyStr, keyStr + v.getUint32(keyStr + 4, true));
  const chunks = [];
  for (let p = keyStr + keys.length; p < u8.length; p += v.getUint32(p + 4, true)) {
    chunks.push(u8.subarray(p, p + v.getUint32(p + 4, true)));
  }
  return { id: v.getUint32(8, true), header, types, keys, chunks };
}

function remapType(chunk, keyMap, strBase) {
  const c = chunk.slice();
  const v = view(c);
  const hsz = v.getUint16(2, true);
  const flags = c[9];
  const n = v.getUint32(12, true);
  const entriesStart = v.getUint32(16, true);
  const offs = [];

  if (flags & TYPE_SPARSE) {
    for (let i = 0; i < n; i++) offs.push(v.getUint16(hsz + 4 * i + 2, true) * 4);
  } else if (flags & TYPE_OFFSET16) {
    for (let i = 0; i < n; i++) {
      const o = v.getUint16(hsz + 2 * i, true);
      if (o !== 0xffff) offs.push(o * 4);
    }
  } else {
    for (let i = 0; i < n; i++) {
      const o = v.getUint32(hsz + 4 * i, true);
      if (o !== 0xffffffff) offs.push(o);
    }
  }

  const fixValue = (at) => {
    if (c[at + 3] === TYPE_STRING) {
      v.setUint32(at + 4, v.getUint32(at + 4, true) + strBase, true);
    }
  };

  for (const o of offs) {
    const e = entriesStart + o;
    const ef = v.getUint16(e + 2, true);
    if (ef & FLAG_COMPACT) {
      const k = keyMap[v.getUint16(e, true)];
      if (k > 0xffff) throw new Error('compact ARSC entry key overflow');
      v.setUint16(e, k, true);
      if ((ef >> 8) === TYPE_STRING) {
        v.setUint32(e + 4, v.getUint32(e + 4, true) + strBase, true);
      }
      continue;
    }
    v.setUint32(e + 4, keyMap[v.getUint32(e + 4, true)], true);
    if (ef & FLAG_COMPLEX) {
      const esz = v.getUint16(e, true);
      const cnt = v.getUint32(e + 12, true);
      for (let m = 0; m < cnt; m++) fixValue(e + esz + 12 * m + 4);
    } else {
      fixValue(e + v.getUint16(e, true));
    }
  }
  return c;
}

/**
 * Merge config-split `resources.arsc` tables into base's table, optionally renaming the package.
 * If `splitU8s` is empty and `packageName` is null, returns `baseU8` unchanged.
 */
export function mergeArsc(baseU8, splitU8s = [], packageName = null) {
  if (!splitU8s.length && !packageName) return baseU8;

  const base = parseTable(baseU8);
  const gp = parsePool(base.pool);
  const kp = parsePool(base.pkg.keys);
  const keyIdx = new Map(kp.raws.map((r, i) => [decodeRaw(r, kp.utf8), i]));
  const baseTypePool = parsePool(base.pkg.types);
  const baseTypeNames = baseTypePool.raws.map((r) => decodeRaw(r, baseTypePool.utf8));
  const chunks = [...base.pkg.chunks];

  for (const su8 of splitU8s) {
    const s = parseTable(su8);
    if (s.pkg.id !== base.pkg.id) throw new Error('split ARSC package id differs from base');
    const sgp = parsePool(s.pool);
    if (sgp.utf8 !== gp.utf8) throw new Error('ARSC string pool encodings differ');
    const strBase = gp.raws.length;
    gp.raws.push(...sgp.raws);

    const skp = parsePool(s.pkg.keys);
    const keyMap = skp.raws.map((r) => {
      const k = decodeRaw(r, skp.utf8);
      if (!keyIdx.has(k)) {
        if (skp.utf8 !== kp.utf8) throw new Error('ARSC key pool encodings differ');
        keyIdx.set(k, kp.raws.length);
        kp.raws.push(r);
      }
      return keyIdx.get(k);
    });

    const sTypePool = parsePool(s.pkg.types);
    const sTypeNames = sTypePool.raws.map((r) => decodeRaw(r, sTypePool.utf8));

    for (const ch of s.pkg.chunks) {
      const t = view(ch).getUint16(0, true);
      const id = ch[8];
      if (t === 0x0202) {
        if (sTypeNames[id - 1] !== baseTypeNames[id - 1]) {
          throw new Error(`ARSC type ${id} name mismatch`);
        }
        const bi = chunks.findIndex((c) => view(c).getUint16(0, true) === 0x0202 && c[8] === id);
        if (bi < 0) {
          chunks.push(ch);
          continue;
        }
        const spec = chunks[bi].slice();
        const sv = view(spec);
        const xv = view(ch);
        const n = Math.min(sv.getUint32(12, true), xv.getUint32(12, true));
        for (let i = 0; i < n; i++) {
          sv.setUint32(16 + 4 * i, sv.getUint32(16 + 4 * i, true) | xv.getUint32(16 + 4 * i, true), true);
        }
        chunks[bi] = spec;
      } else if (t === 0x0201) {
        const fixed = remapType(ch, keyMap, strBase);
        let at = chunks.length;
        for (let i = chunks.length - 1; i >= 0; i--) {
          if (chunks[i][8] === id && (view(chunks[i]).getUint16(0, true) === 0x0201 || view(chunks[i]).getUint16(0, true) === 0x0202)) {
            at = i + 1;
            break;
          }
        }
        chunks.splice(at, 0, fixed);
        const si = chunks.findIndex((c) => view(c).getUint16(0, true) === 0x0202 && c[8] === id);
        if (si >= 0) {
          chunks[si] = chunks[si].slice();
          const sv = view(chunks[si]);
          sv.setUint16(10, sv.getUint16(10, true) + 1, true);
        }
      }
    }
  }

  const newKeys = buildPool(kp);
  const newPool = buildPool(gp);
  const hdr = base.pkg.header.slice();
  const hv = view(hdr);
  if (packageName) {
    if (packageName.length > 127) throw new Error('package name exceeds 127 chars');
    for (let i = 0; i < 128; i++) {
      hv.setUint16(12 + 2 * i, i < packageName.length ? packageName.charCodeAt(i) : 0, true);
    }
  }
  hv.setUint32(268, hdr.length, true);
  hv.setUint32(276, hdr.length + base.pkg.types.length, true);
  let chunksLen = 0;
  for (const c of chunks) chunksLen += c.length;
  const pkgSize = hdr.length + base.pkg.types.length + newKeys.length + chunksLen;
  hv.setUint32(4, pkgSize, true);

  const total = 12 + newPool.length + pkgSize;
  const out = new Uint8Array(total);
  const ov = view(out);
  ov.setUint16(0, 0x0002, true);
  ov.setUint16(2, 12, true);
  ov.setUint32(4, total, true);
  ov.setUint32(8, 1, true);
  let o = 12;
  for (const part of [newPool, hdr, base.pkg.types, newKeys, ...chunks]) {
    out.set(part, o);
    o += part.length;
  }
  return out;
}


