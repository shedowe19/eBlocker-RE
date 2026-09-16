#!/usr/bin/env python3
"""Deterministic synthetic RDB writer for regression fixtures; never used by production.

Encoding reference: redis/redis 7.2 src/{rdb.c,ziplist.c,listpack.c,intset.c}.
The independent expected logical dataset is emitted alongside the binary fixture.
"""
import base64
import json
import struct
from pathlib import Path

TARGET = Path(__file__).resolve().parents[1] / 'resources/rdb'


def length(value):
    if value < 64:
        return bytes([value])
    if value < 16384:
        return bytes([0x40 | (value >> 8), value & 255])
    return b'\x80' + struct.pack('>I', value)


def string(value):
    return length(len(value)) + value


def ziplist(values):
    body = b''
    previous = 0
    tail = 10
    for value in values:
        tail = 10 + len(body)
        entry = bytes([previous]) + string(value)
        body += entry
        previous = len(entry)
    return struct.pack('<IIH', len(body) + 11, tail, len(values)) + body + b'\xff'


def listpack(values):
    body = b''
    for value in values:
        assert len(value) < 63
        encoded = bytes([0x80 | len(value)]) + value
        body += encoded + bytes([len(encoded)])
    return struct.pack('<IH', len(body) + 7, len(values)) + body + b'\xff'


def checksum(data):
    value = 0
    for byte in data:
        value ^= byte
        for _ in range(8):
            value = (value >> 1) ^ (0x95ac9329ac4bc9b5 if value & 1 else 0)
    return struct.pack('<Q', value)


def write():
    b64 = lambda value: base64.b64encode(value).decode()
    source = bytearray(b'REDIS0011')
    source += b'\xfa' + string(b'redis-ver') + string(b'7.2.0')
    source += b'\xfa' + string(b'ctime') + string(b'1700000000')
    entries = []
    database = 0
    source += b'\xfe\x00'

    def add(name, rdb_type, payload, kind, values, expiry=None, seconds=False):
        if isinstance(name, str):
            name = name.encode()
        if expiry is not None:
            source.extend((b'\xfd' + struct.pack('<I', expiry // 1000)) if seconds
                          else b'\xfc' + struct.pack('<q', expiry))
        source.extend(bytes([rdb_type]) + string(name) + payload)
        entry = {'database': database, 'key': b64(name), 'type': kind}
        if expiry is not None:
            entry['expiresAtEpochMs'] = expiry
        if kind == 'STRING':
            entry['value'] = b64(values)
        elif kind == 'HASH':
            entry['hash'] = [{'field': b64(k), 'value': b64(v)} for k, v in values]
        elif kind == 'SORTED_SET':
            entry['sortedSet'] = [{'member': b64(k), 'scoreBits': struct.pack('>d', v).hex()} for k, v in values]
        else:
            entry[kind.lower()] = list(map(b64, values))
        entries.append(entry)

    add(b'\x00\xffkey', 0, string(b'\x00\xffvalue'), 'STRING', b'\x00\xffvalue')
    add(b'', 0, string(b''), 'STRING', b'')
    add('expired-ms', 0, string(b'historical'), 'STRING', b'historical', 1699999999123)
    add('expired-sec', 0, string(b'historical'), 'STRING', b'historical', 1699999999000, True)
    add('future', 0, string(b'future'), 'STRING', b'future', 4102444800123)
    for bits, value, fmt in [(8, -42, '<b'), (16, -12345, '<h'), (32, -123456789, '<i')]:
        add('integer' + str(bits), 0, bytes([{8: 192, 16: 193, 32: 194}[bits]]) + struct.pack(fmt, value), 'STRING', str(value).encode())
    value = b'\x00\xffcompressed'
    compressed = bytes([len(value) - 1]) + value  # Valid LZF literal block.
    add('lzf', 0, b'\xc3' + length(len(compressed)) + length(len(value)) + compressed, 'STRING', value)
    fields = [(b'', b''), (b'\x00field', b'\xffvalue')]
    flattened = [item for field in fields for item in field]
    add('hash', 4, length(len(fields)) + b''.join(map(string, flattened)), 'HASH', fields)
    zipmap = bytes([len(fields)]) + b''.join(length(len(k)) + k + length(len(v)) + b'\x00' + v for k, v in fields) + b'\xff'
    add('zipmap', 9, string(zipmap), 'HASH', fields)
    add('hash-ziplist', 13, string(ziplist(flattened)), 'HASH', fields)
    add('hash-listpack', 16, string(listpack(flattened)), 'HASH', fields)
    values = [b'', b'\x00\xff', b'first', b'first']
    add('list', 1, length(len(values)) + b''.join(map(string, values)), 'LIST', values)
    add('list-ziplist', 10, string(ziplist(values)), 'LIST', values)
    add('quicklist', 14, b'\x01' + string(ziplist(values)), 'LIST', values)
    add('quicklist2', 18, b'\x02\x02' + string(listpack(values)) + b'\x01' + string(b'x' * 200), 'LIST', values + [b'x' * 200])
    values = [b'', b'\x00\xff', b'first']
    add('set', 2, length(len(values)) + b''.join(map(string, values)), 'SET', values)
    add('set-listpack', 20, string(listpack(values)), 'SET', values)
    for size, fmt, ints in [(2, '<h', [-32768, 0, 32767]), (4, '<i', [-2147483648, 0, 2147483647]), (8, '<q', [-9223372036854775808, 0, 9223372036854775807])]:
        data = struct.pack('<II', size, len(ints)) + b''.join(struct.pack(fmt, n) for n in ints)
        add('intset' + str(size * 8), 11, string(data), 'SET', [str(n).encode() for n in ints])
    scores = [(b'negative-zero', -0.0), (b'precise', 1.0000000000000002), (b'inf', float('inf')), (b'-inf', float('-inf'))]
    data = length(len(scores))
    for member, score in scores:
        encoded = b'\xfe' if score == float('inf') else b'\xff' if score == float('-inf') else bytes([len(repr(score))]) + repr(score).encode()
        data += string(member) + encoded
    add('zset-text', 3, data, 'SORTED_SET', scores)
    add('zset-binary', 5, length(len(scores)) + b''.join(string(k) + struct.pack('<d', v) for k, v in scores), 'SORTED_SET', scores)
    compact = [(b'\x00\xff', 1.0000000000000002), (b'', -0.0)]
    flat = [item for k, v in compact for item in (k, repr(v).encode())]
    add('zset-ziplist', 12, string(ziplist(flat)), 'SORTED_SET', compact)
    add('zset-listpack', 17, string(listpack(flat)), 'SORTED_SET', compact)
    database = 2
    source += b'\xfe\x02'
    add(b'\x00\xffkey', 0, string(b'database-two'), 'STRING', b'database-two')
    source += b'\xfe\x07\xff'  # Explicit empty database, then EOF.
    source += checksum(source)
    TARGET.mkdir(parents=True, exist_ok=True)
    (TARGET / 'canonical-all-encodings.rdb').write_bytes(source)
    expected = {'format': 'eblocker-redis-logical', 'version': 1, 'capturedAtEpochMs': 1700000000000, 'databases': [0, 2, 7], 'entries': entries}
    (TARGET / 'canonical-all-encodings.expected.json').write_text(json.dumps(expected, indent=2) + '\n')


if __name__ == '__main__':
    write()
