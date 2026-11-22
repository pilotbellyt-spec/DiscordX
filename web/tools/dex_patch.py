"""Generate gzip-compressed DXD2 deltas from BSDIFF control/difference blocks."""
import gzip
import struct
from bsdiff4.core import diff


def signed_offset(data):
    value = int.from_bytes(data, 'little')
    return -(value & ((1 << 63) - 1)) if value >> 63 else value


def create_patch(source, target):
    controls, differences, additions = diff(source, target)
    count = len(controls) // 24
    patch = bytearray(b'DXD2' + struct.pack('<IIIII', len(source), len(target), count,
                                          len(differences), len(additions)))
    for offset in range(0, len(controls), 24):
        copy, add, seek = (signed_offset(controls[pos:pos + 8])
                           for pos in range(offset, offset + 24, 8))
        patch.extend(struct.pack('<IIi', copy, add, seek))
    patch.extend(differences)
    patch.extend(additions)
    return gzip.compress(patch, mtime=0)
