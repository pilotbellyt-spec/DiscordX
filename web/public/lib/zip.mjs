// Zero-dependency ZIP reader & 16 KiB-aligned APK writer for browser and Node.js.
// Copies unmodified entries raw without re-compressing, and aligns stored `.so` files to
// 16 KiB (Android 15/16 page-size compliant) and other stored entries to 4 bytes.

const te = new TextEncoder();
const td = new TextDecoder();

const CRC_TABLE = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  CRC_TABLE[i] = c >>> 0;
}

export function crc32(u8) {
  let c = 0xffffffff;
  for (let i = 0; i < u8.length; i++) c = CRC_TABLE[(c ^ u8[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

const dv = (u8) => new DataView(u8.buffer, u8.byteOffset, u8.byteLength);

/**
 * Parse a ZIP/APK buffer into entry descriptors referencing slices of `u8`.
 * @param {Uint8Array} u8
 * @returns {Array<{name:string, method:number, crc:number, csize:number, usize:number, raw:Uint8Array}>}
 */
export function readZip(u8) {
  const v = dv(u8);
  const minOff = Math.max(0, u8.length - 65557);
  let eocd = -1;
  for (let p = u8.length - 22; p >= minOff; p--) {
    if (v.getUint32(p, true) === 0x06054b50 && p + 22 + v.getUint16(p + 20, true) === u8.length) {
      eocd = p;
      break;
    }
  }
  if (eocd < 0) throw new Error('not a valid ZIP/APK archive (missing EOCD)');

  const count = v.getUint16(eocd + 10, true);
  let p = v.getUint32(eocd + 16, true);
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (v.getUint32(p, true) !== 0x02014b50) throw new Error('corrupt ZIP central directory');
    const method = v.getUint16(p + 10, true);
    const crc = v.getUint32(p + 16, true);
    const csize = v.getUint32(p + 20, true);
    const usize = v.getUint32(p + 24, true);
    const nl = v.getUint16(p + 28, true);
    const el = v.getUint16(p + 30, true);
    const cl = v.getUint16(p + 32, true);
    const lho = v.getUint32(p + 42, true);
    const name = td.decode(u8.subarray(p + 46, p + 46 + nl));
    p += 46 + nl + el + cl;

    if (v.getUint32(lho, true) !== 0x04034b50) throw new Error(`corrupt local header for ${name}`);
    const lnl = v.getUint16(lho + 26, true);
    const lel = v.getUint16(lho + 28, true);
    const dataOff = lho + 30 + lnl + lel;
    entries.push({ name, method, crc, csize, usize, raw: u8.subarray(dataOff, dataOff + csize) });
  }
  return entries;
}

/** Decompress (if deflated) and return entry bytes as a Uint8Array. */
export async function entryData(e) {
  if (e.method === 0) return e.raw;
  if (e.method !== 8) throw new Error(`unsupported ZIP compression method ${e.method} for ${e.name}`);
  const ds = new DecompressionStream('deflate-raw');
  const w = ds.writable.getWriter();
  const writeP = w.write(e.raw).then(() => w.close());
  const out = new Uint8Array(await new Response(ds.readable).arrayBuffer());
  await writeP;
  return out;
}

/** Create an uncompressed (STORED) entry descriptor. */
export function storedEntry(name, data) {
  const raw = data instanceof Uint8Array ? data : new Uint8Array(data);
  return { name, method: 0, crc: crc32(raw), csize: raw.length, usize: raw.length, raw };
}

/** Create a DEFLATE-compressed entry descriptor. */
export async function deflatedEntry(name, data) {
  const plain = data instanceof Uint8Array ? data : new Uint8Array(data);
  const cs = new CompressionStream('deflate-raw');
  const w = cs.writable.getWriter();
  const writeP = w.write(plain).then(() => w.close());
  const raw = new Uint8Array(await new Response(cs.readable).arrayBuffer());
  await writeP;
  return { name, method: 8, crc: crc32(plain), csize: raw.length, usize: plain.length, raw };
}

/**
 * Assemble an unsigned ZIP/APK as an array of Uint8Array chunks (zero-copy for entry payloads).
 * Stored `.so` files are aligned to 16 KiB; all other stored entries are aligned to 4 bytes.
 */
export function writeZip(entries) {
  const parts = [];
  const cd = [];
  let off = 0;

  for (const e of entries) {
    const name = te.encode(e.name);
    let pad = 0;
    if (e.method === 0) {
      const align = e.name.startsWith('lib/') && e.name.endsWith('.so') ? 16384 : 4;
      pad = (align - ((off + 30 + name.length) % align)) % align;
    }
    const lh = new Uint8Array(30 + name.length + pad);
    const lv = dv(lh);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, e.method === 0 ? 10 : 20, true);
    lv.setUint16(6, 0, true);
    lv.setUint16(8, e.method, true);
    lv.setUint32(10, 0x00210000, true); // fixed DOS timestamp (1980-01-01)
    lv.setUint32(14, e.crc >>> 0, true);
    lv.setUint32(18, e.csize >>> 0, true);
    lv.setUint32(22, e.usize >>> 0, true);
    lv.setUint16(26, name.length, true);
    lv.setUint16(28, pad, true);
    lh.set(name, 30);
    parts.push(lh, e.raw);
    cd.push({ e, name, off });
    off += lh.length + e.raw.length;
  }

  const cdStart = off;
  for (const { e, name, off: lho } of cd) {
    const c = new Uint8Array(46 + name.length);
    const cv = dv(c);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, e.method === 0 ? 10 : 20, true);
    cv.setUint16(10, e.method, true);
    cv.setUint32(12, 0x00210000, true);
    cv.setUint32(16, e.crc >>> 0, true);
    cv.setUint32(20, e.csize >>> 0, true);
    cv.setUint32(24, e.usize >>> 0, true);
    cv.setUint16(28, name.length, true);
    cv.setUint32(42, lho >>> 0, true);
    c.set(name, 46);
    parts.push(c);
    off += c.length;
  }

  const cdSize = off - cdStart;
  const eocd = new Uint8Array(22);
  const ev = dv(eocd);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, cd.length, true);
  ev.setUint16(10, cd.length, true);
  ev.setUint32(12, cdSize >>> 0, true);
  ev.setUint32(16, cdStart >>> 0, true);
  parts.push(eocd);

  if (cd.length > 0xffff || off > 0xffffffff) throw new Error('ZIP64 not supported');
  return { parts, cdOffset: cdStart, cdSize };
}


