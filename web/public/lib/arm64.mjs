// Find and patch the Phoenix access gate and the staff check in an arm64
// liblibdiscord_wasm_host.so. JS port of patcher/sopatch.py (arm64, hand-decoded; x86_64 is in x86.js,
// which reuses Elf; findRegistrySlots/renameNeeded work for both).
//
// access:   only function referencing the experiment table {"2026-11-phoenix-app-access", 26}
//           -> return Some(true)
// is_staff: call right after dev_analytics_env() in the Dev Tools settings builder (the function
//           referencing "Show Dev Tools Widget"). Its key/read_state/drop/flag_override offsets are
//           read from it, then its body becomes: take the state read-guard, set
//           flag_override = Some(STAFF), drop the guard, return true.

const EXPERIMENT = '2026-11-phoenix-app-access';
const DEVTOOLS_ROW = 'Show Dev Tools Widget';
const DEV_ENV = 'LIBDISCORD_DEV_ANALYTICS';
const R_RELATIVE = { 183: 1027, 62: 8 }; // EM_AARCH64, EM_X86_64
const te = new TextEncoder();

export class Elf {
  constructor(u8) {
    this.u8 = u8;
    this.v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    const v = this.v;
    if (v.getUint32(0) !== 0x7f454c46 || u8[4] !== 2) throw new Error('not an ELF64 file');
    this.rtype = R_RELATIVE[v.getUint16(18, true)];
    if (!this.rtype) throw new Error('not an arm64 or x86_64 library');
    const phoff = Number(v.getBigUint64(32, true)), shoff = Number(v.getBigUint64(40, true));
    const phentsize = v.getUint16(54, true), phnum = v.getUint16(56, true);
    const shentsize = v.getUint16(58, true), shnum = v.getUint16(60, true), shstrndx = v.getUint16(62, true);
    this.loads = [];
    for (let i = 0; i < phnum; i++) {
      const b = phoff + i * phentsize;
      if (v.getUint32(b, true) === 1)
        this.loads.push([Number(v.getBigUint64(b + 16, true)), Number(v.getBigUint64(b + 8, true)), Number(v.getBigUint64(b + 32, true))]);
    }
    const sec = [];
    for (let i = 0; i < shnum; i++) {
      const b = shoff + i * shentsize;
      sec.push([v.getUint32(b, true), Number(v.getBigUint64(b + 16, true)), Number(v.getBigUint64(b + 24, true)), Number(v.getBigUint64(b + 32, true))]);
    }
    const stro = sec[shstrndx][2];
    this.sec = {};
    for (const [n, addr, off, size] of sec) {
      let e = stro + n;
      while (u8[e]) e++;
      this.sec[new TextDecoder().decode(u8.subarray(stro + n, e))] = { addr, off, size };
    }
  }

  off(va) {
    for (const [v, o, sz] of this.loads) if (va >= v && va < v + sz) return va - v + o;
    throw new Error(`unmapped address 0x${va.toString(16)}`);
  }
  u32(va) { return this.v.getUint32(this.off(va), true); }
  u64(va) { return Number(this.v.getBigUint64(this.off(va), true)); }

  findStr(s) {
    const { addr, off, size } = this.sec['.rodata'];
    const needle = te.encode(s), hay = this.u8, out = [];
    const end = off + size - needle.length;
    outer: for (let i = hay.indexOf(needle[0], off); i !== -1 && i <= end; i = hay.indexOf(needle[0], i + 1)) {
      for (let j = 1; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer;
      out.push(addr + i - off);
    }
    return out;
  }

  // R_*_RELATIVE: slot -> addend, from Android APS2-packed .rela.dyn
  relocs() {
    if (this._rel) return this._rel;
    const { off, size } = this.sec['.rela.dyn'];
    const d = this.u8, out = new Map();
    if (d[off] !== 0x41 || d[off + 1] !== 0x50 || d[off + 2] !== 0x53 || d[off + 3] !== 0x32) {
      for (let i = off; i < off + size; i += 24) {
        if (Number(this.v.getBigUint64(i + 8, true) & 0xffffffffn) === this.rtype)
          out.set(Number(this.v.getBigUint64(i, true)), Number(this.v.getBigInt64(i + 16, true)));
      }
      return (this._rel = out);
    }
    let p = off + 4;
    const sl = () => {
      let r = 0n, s = 0n, b;
      do { b = d[p++]; r |= BigInt(b & 0x7f) << s; s += 7n; } while (b & 0x80);
      if (b & 0x40) r -= 1n << s;
      return r;
    };
    const n = Number(sl());
    let roff = sl(), add = 0n, cnt = 0;
    while (cnt < n) {
      const gs = Number(sl()), fl = Number(sl());
      const god = fl & 2 ? sl() : 0n;
      let info = fl & 1 ? sl() : 0n;
      if (fl & 8 && fl & 4) add += sl();
      for (let k = 0; k < gs; k++, cnt++) {
        roff += fl & 2 ? god : sl();
        if (!(fl & 1)) info = sl();
        if (fl & 8 && !(fl & 4)) add += sl();
        if (Number(info & 0xffffffffn) === this.rtype) out.set(Number(roff), Number(fl & 8 ? add : 0n));
      }
    }
    return (this._rel = out);
  }

  slotsTo(target) {
    const r = [];
    for (const [slot, a] of this.relocs()) if (a === target) r.push(slot);
    return r;
  }

  // function starts from .eh_frame_hdr
  starts() {
    if (this._starts) return this._starts;
    const { addr, off } = this.sec['.eh_frame_hdr'];
    const cnt = this.v.getUint32(off + 8, true);
    const a = new Float64Array(cnt);
    for (let i = 0; i < cnt; i++) a[i] = this.v.getInt32(off + 12 + i * 8, true) + addr;
    a.sort();
    return (this._starts = a);
  }
  func(a) {
    const s = this.starts();
    let lo = 0, hi = s.length - 1;
    while (lo < hi) { const m = (lo + hi + 1) >> 1; if (s[m] <= a) lo = m; else hi = m - 1; }
    return [s[lo], lo + 1 < s.length ? s[lo + 1] : a + 0x1000];
  }

  // adrp + add/ldr pairs -> target
  xrefs() {
    if (this._x) return this._x;
    const { addr, off, size } = this.sec['.text'];
    const w = new Uint32Array(this.u8.buffer.slice(this.u8.byteOffset + off, this.u8.byteOffset + off + (size & ~3)));
    const out = new Map();
    for (let i = 0; i < w.length; i++) {
      const a = w[i];
      if ((a & 0x9f000000) >>> 0 !== 0x90000000) continue;
      const rd = a & 31, pc = addr + i * 4;
      let imm = ((a >>> 29) & 3) | (((a >>> 5) & 0x7ffff) << 2);
      if (imm & (1 << 20)) imm -= 1 << 21;
      const page = Math.floor(pc / 4096) * 4096 + imm * 4096;
      for (let k = 1; k <= 4 && i + k < w.length; k++) {
        const n = w[i + k];
        if (((n >>> 5) & 31) !== rd) continue;
        let t = -1;
        if ((n & 0xffc00000) >>> 0 === 0x91000000) t = page + ((n >>> 10) & 0xfff);
        else if ((n & 0xffc00000) >>> 0 === 0xf9400000) t = page + ((n >>> 10) & 0xfff) * 8;
        if (t >= 0) { if (!out.has(t)) out.set(t, []); out.get(t).push(pc); }
      }
    }
    return (this._x = out);
  }
  refs(t) { return [...new Set(this.xrefs().get(t) || [])].sort((a, b) => a - b); }

  write(va, words) {
    const o = this.off(va);
    words.forEach((w, i) => this.v.setUint32(o + i * 4, w >>> 0, true));
  }
}

export const one = (xs, what) => {
  const u = [...new Set(xs)];
  if (u.length !== 1) throw new Error(`${what}: expected 1 match, got ${u.length}`);
  return u[0];
};
const blTarget = (w, pc) => ((w & 0xfc000000) >>> 0 === 0x94000000 ? pc + (((w << 6) >> 6) * 4) : null);

function findAccess(e) {
  const hits = [];
  for (const s of e.findStr(EXPERIMENT))
    for (const slot of e.slotsTo(s))
      if (e.u64(slot + 8) === EXPERIMENT.length) for (const r of e.refs(slot)) hits.push(e.func(r)[0]);
  return one(hits, 'access evaluator');
}


export function accessEdit(bytes) {
 const elf = new Elf(bytes);
 if (elf.rtype !== 1027) throw new Error('Expected arm64 ELF');
 const offset = elf.off(findAccess(elf));
 return {offset, original: bytes.slice(offset, offset+8), patched: new Uint8Array([32,0,128,82,192,3,95,214])};
}

