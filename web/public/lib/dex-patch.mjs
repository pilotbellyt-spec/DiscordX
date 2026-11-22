export async function applyDexPatch(source, compressed) {
  const invalid = () => { throw new Error('Invalid DiscordX host patch.'); };
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('gzip'));
  const patch = new Uint8Array(await new Response(stream).arrayBuffer());
  if (patch.length < 24 || String.fromCharCode(...patch.subarray(0, 4)) !== 'DXD2') invalid();
  const view = new DataView(patch.buffer, patch.byteOffset, patch.byteLength);
  const sourceSize = view.getUint32(4, true), targetSize = view.getUint32(8, true);
  const count = view.getUint32(12, true), diffSize = view.getUint32(16, true), extraSize = view.getUint32(20, true);
  const diffStart = 24 + count * 12, extraStart = diffStart + diffSize;
  if (sourceSize !== source.length || targetSize === 0 || targetSize > 64 * 1024 * 1024
      || extraStart + extraSize !== patch.length) invalid();
  const target = new Uint8Array(targetSize);
  let old = 0, written = 0, diff = diffStart, extra = extraStart;
  for (let i = 0; i < count; i++) {
    const control = 24 + i * 12;
    const copy = view.getUint32(control, true), add = view.getUint32(control + 4, true);
    const seek = view.getInt32(control + 8, true);
    if (written + copy + add > targetSize || diff + copy > extraStart || extra + add > patch.length) invalid();
    for (let j = 0; j < copy; j++) {
      const original = old + j;
      target[written + j] = patch[diff + j] + (original >= 0 && original < source.length ? source[original] : 0);
    }
    written += copy;
    diff += copy;
    target.set(patch.subarray(extra, extra + add), written);
    written += add;
    extra += add;
    old += copy + seek;
  }
  if (written !== targetSize || diff !== extraStart || extra !== patch.length) invalid();
  return target;
}
