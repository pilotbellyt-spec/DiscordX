// APK Signature Scheme v2 using WebCrypto (RSA-2048, PKCS#1 v1.5, SHA-256; alg 0x0103).
// Operates directly on the chunk list produced by zip.writeZip() without allocating a flat copy.

const ALG_RSA_PKCS1_SHA256 = 0x0103;
const V2_BLOCK_ID = 0x7109871a;
const CHUNK_SIZE = 1 << 20; // 1 MiB APK v2 chunk size
const ALGO = { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' };
const MAGIC = new TextEncoder().encode('APK Sig Block 42');

function cat(...arrays) {
  let len = 0;
  for (const a of arrays) len += a.length;
  const out = new Uint8Array(len);
  let pos = 0;
  for (const a of arrays) {
    out.set(a, pos);
    pos += a.length;
  }
  return out;
}

function u32(n) {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, n >>> 0, true);
  return b;
}

function u64(n) {
  const b = new Uint8Array(8);
  new DataView(b.buffer).setBigUint64(0, BigInt(n), true);
  return b;
}

const lp = (b) => cat(u32(b.length), b);

function* chunksOf(parts) {
  const buf = new Uint8Array(CHUNK_SIZE);
  let fill = 0;
  for (const p of parts) {
    let o = 0;
    while (o < p.length) {
      const take = Math.min(CHUNK_SIZE - fill, p.length - o);
      buf.set(p.subarray(o, o + take), fill);
      fill += take;
      o += take;
      if (fill === CHUNK_SIZE) {
        yield buf.slice();
        fill = 0;
      }
    }
  }
  if (fill > 0) yield buf.slice(0, fill);
}

async function computeContentDigest(sections, onChunk) {
  const subtle = globalThis.crypto.subtle;
  const digests = [];
  let count = 0;
  for (const sec of sections) {
    for (const chunk of chunksOf(sec)) {
      const d = await subtle.digest('SHA-256', cat(new Uint8Array([0xa5]), u32(chunk.length), chunk));
      digests.push(new Uint8Array(d));
      count++;
      if (onChunk && (count & 15) === 0) onChunk(count);
    }
  }
  const top = await subtle.digest('SHA-256', cat(new Uint8Array([0x5a]), u32(digests.length), ...digests));
  return new Uint8Array(top);
}

/**
 * Decode a JSON signing identity ({pkcs8, spki, cert} base64 strings) into Uint8Arrays.
 */
export function decodeIdentity(json) {
  const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  return {
    pkcs8: b64(json.pkcs8),
    spki: b64(json.spki),
    cert: b64(json.cert),
  };
}

/**
 * Sign `{parts, cdOffset, cdSize}` from `writeZip()` with APK Signature Scheme v2.
 * Returns a Blob-ready `Uint8Array[]` array.
 */
export async function signV2(zip, identity, log = () => {}) {
  const subtle = globalThis.crypto.subtle;
  const { parts, cdOffset } = zip;
  const eocd = parts[parts.length - 1];
  const entries = [];
  const cd = [];
  let pos = 0;
  for (let i = 0; i < parts.length - 1; i++) {
    const p = parts[i];
    (pos < cdOffset ? entries : cd).push(p);
    pos += p.length;
  }

  log('signing: hashing APK chunks (SHA-256)…');
  const digest = await computeContentDigest([entries, cd, [eocd]]);

  const signedData = cat(
    lp(lp(cat(u32(ALG_RSA_PKCS1_SHA256), lp(digest)))),
    lp(lp(identity.cert)),
    lp(new Uint8Array(0)),
  );
  const key = await subtle.importKey('pkcs8', identity.pkcs8, ALGO, false, ['sign']);
  const sig = new Uint8Array(await subtle.sign('RSASSA-PKCS1-v1_5', key, signedData));

  const signer = cat(
    lp(signedData),
    lp(lp(cat(u32(ALG_RSA_PKCS1_SHA256), lp(sig)))),
    lp(identity.spki),
  );
  const value = lp(lp(signer));
  const pair = cat(u64(4 + value.length), u32(V2_BLOCK_ID), value);
  const blockSize = pair.length + 8 + MAGIC.length;
  const block = cat(u64(blockSize), pair, u64(blockSize), MAGIC);

  const newEocd = eocd.slice();
  new DataView(newEocd.buffer).setUint32(16, (cdOffset + block.length) >>> 0, true);
  return [...entries, block, ...cd, newEocd];
}


