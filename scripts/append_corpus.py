#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Conservative append-only corpus merge for yuedu-MCP.

Reads a base corpus directory (index.json + shards/) produced by
build_corpus.py, plus a merged Legado bookSource export, and writes a NEW
output directory where:

- every base index rec/fam value and every base shard byte is preserved
  verbatim (files are copied byte-for-byte);
- incoming sources whose identity key already exists in the base are
  skipped (base wins, never overwritten);
- genuinely new sources are cleaned/clustered via build_corpus.py, then
  re-numbered: rec.i / sid gain +base.n and family ids continue after the
  base's max fid;
- index.json gains a `provenance` array with the merge audit entry.

No JS inside sources is executed and no network is used.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_corpus  # noqa: E402

_FID_RE = re.compile(r'^f_\d{4}$')
_PART_RE = re.compile(r'^f_(\d{4})-(\d+)\.json$')
# Same normalization as yuedu-source-hub/scripts/source-quality.mjs:
# only http/https scheme+host case, default port, empty root path and empty
# fragment are folded; userinfo/www/path/query (including a bare '?') and
# non-empty fragments are kept verbatim; non-http(s) or scheme-less ids are
# taken as-is.
_AUTH_URL_RE = re.compile(r'^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([\s\S]*)$')


def split_authority(authority):
    at = authority.rfind('@')
    userinfo = authority[:at + 1] if at >= 0 else ''
    hp = authority[at + 1:]
    host, port = hp, ''
    if hp.startswith('['):
        rb = hp.find(']')
        if rb >= 0:
            rest = hp[rb + 1:]
            if re.match(r'^:\d+$', rest):
                host, port = hp[:rb + 1], rest[1:]
            else:
                host = hp
    else:
        colon = hp.rfind(':')
        if colon > -1 and re.match(r'^\d*$', hp[colon + 1:]):
            host, port = hp[:colon], hp[colon + 1:]
    return userinfo, host, port


def identity_key(raw_url, source_type):
    u = str(raw_url or '').strip()
    t = str(0 if source_type is None else source_type)
    if not u:
        return t + '|'
    m = _AUTH_URL_RE.match(u)
    if m:
        scheme = m.group(1).lower()
        if scheme in ('http', 'https'):
            userinfo, host, port = split_authority(m.group(2))
            suffix = m.group(3) or ''
            frag = ''
            hi = suffix.find('#')
            if hi >= 0:
                frag = suffix[hi + 1:]
                suffix = suffix[:hi]
            qi = suffix.find('?')
            path = suffix[:qi] if qi >= 0 else suffix
            query = suffix[qi:] if qi >= 0 else ''
            if path == '/':
                path = ''
            default_port = (scheme == 'http' and port == '80') \
                or (scheme == 'https' and port == '443')
            port_part = ':' + port if (port and not default_port) else ''
            frag_part = '#' + frag if frag else ''
            return '%s|%s://%s%s%s%s%s%s' % (
                t, scheme, userinfo, host.lower(), port_part,
                path, query, frag_part)
        return '%s|%s' % (t, u)
    return '%s|%s' % (t, u)


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def _read_json(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


def load_and_validate_base(base_dir):
    """Strictly validate a build_corpus output directory."""
    if not os.path.isdir(base_dir):
        raise ValueError('base is not a directory: %s' % base_dir)
    index_path = os.path.join(base_dir, 'index.json')
    shards_dir = os.path.join(base_dir, 'shards')
    if not os.path.isfile(index_path):
        raise ValueError('base index.json missing: %s' % index_path)
    if not os.path.isdir(shards_dir):
        raise ValueError('base shards/ missing: %s' % shards_dir)

    index = _read_json(index_path)
    if not isinstance(index, dict):
        raise ValueError('base index is not an object')
    for key in ('v', 'n', 'gen', 'fam', 'rec'):
        if key not in index:
            raise ValueError('base index missing key: %s' % key)
    rec, fam = index['rec'], index['fam']
    if not isinstance(rec, list) or not isinstance(fam, dict):
        raise ValueError('base index rec/fam have wrong types')
    if index['n'] != len(rec):
        raise ValueError('base n=%r != len(rec)=%d' % (index['n'], len(rec)))

    for pos, r in enumerate(rec):
        if not isinstance(r, dict):
            raise ValueError('base rec[%d] is not an object' % pos)
        if r.get('i') != pos:
            raise ValueError('base rec[%d].i=%r misaligned' % (pos, r.get('i')))
        fid = r.get('f')
        if not isinstance(fid, str) or not _FID_RE.match(fid) or fid not in fam:
            raise ValueError('base rec[%d].f=%r not a known family' % (pos, fid))

    for fid in fam:
        if not _FID_RE.match(fid):
            raise ValueError('bad family id in base index: %r' % fid)
        meta = fam[fid]
        if not isinstance(meta, dict):
            raise ValueError('base fam[%s] is not an object' % fid)
        for key in ('t', 'm', 'c', 's', 'ex'):
            if key not in meta:
                raise ValueError('base fam[%s] missing key %s' % (fid, key))

    disk_files = {fn for fn in os.listdir(shards_dir)
                  if os.path.isfile(os.path.join(shards_dir, fn))}
    part_map = {}
    for fn in disk_files:
        m = _PART_RE.match(fn)
        if m:
            part_map.setdefault('f_' + m.group(1), []).append((int(m.group(2)), fn))

    expected = set()
    sid2fam = {}
    sid2node = {}
    for fid, meta in fam.items():
        single_name = fid + '.json'
        single = single_name in disk_files
        part_entries = sorted(part_map.get(fid, []))
        if single and part_entries:
            raise ValueError('family %s has both single and part shards' % fid)
        if not single and not part_entries:
            raise ValueError('family %s has no shard file' % fid)
        if part_entries:
            seq = [num for num, _ in part_entries]
            if seq != list(range(len(seq))):
                raise ValueError('family %s part numbers not contiguous: %s'
                                 % (fid, seq))
        parts = [fn for _, fn in part_entries]
        total_bytes = 0
        member_sids = []
        names = [single_name] if single else parts
        for k, fn in enumerate(names):
            path = os.path.join(shards_dir, fn)
            total_bytes += os.path.getsize(path)
            expected.add(fn)
            obj = _read_json(path)
            if not isinstance(obj, dict):
                raise ValueError('shard %s is not an object' % fn)
            if obj.get('f') != fid:
                raise ValueError('shard %s f=%r != %s' % (fn, obj.get('f'), fid))
            if single:
                if 'p' in obj:
                    raise ValueError('single shard %s carries p' % fn)
            elif obj.get('p') != k:
                raise ValueError('part shard %s p=%r != %d' % (fn, obj.get('p'), k))
            ids, nodes = obj.get('ids'), obj.get('n')
            if not isinstance(ids, list) or not isinstance(nodes, list):
                raise ValueError('shard %s ids/n not lists' % fn)
            if len(ids) != len(nodes):
                raise ValueError('shard %s len(ids)=%d != len(n)=%d'
                                 % (fn, len(ids), len(nodes)))
            member_sids.extend(ids)
            for sid, node in zip(ids, nodes):
                if not isinstance(sid, str) or not re.match(r'^i\d+$', sid):
                    raise ValueError('shard %s bad sid %r' % (fn, sid))
                if sid in sid2fam:
                    raise ValueError('duplicate sid %s (%s, %s)'
                                     % (sid, sid2fam[sid], fid))
                if not isinstance(node, dict):
                    raise ValueError('shard %s node for %s not an object'
                                     % (fn, sid))
                sid2fam[sid] = fid
                sid2node[sid] = node
        if meta['c'] != len(member_sids):
            raise ValueError('fam %s c=%d != %d members'
                             % (fid, meta['c'], len(member_sids)))
        if meta['s'] != total_bytes:
            raise ValueError('fam %s s=%d != actual bytes %d'
                             % (fid, meta['s'], total_bytes))
        if not isinstance(meta['ex'], list):
            raise ValueError('fam %s ex not a list' % fid)
        if len(meta['ex']) > 6 or len(set(meta['ex'])) != len(meta['ex']):
            raise ValueError('fam %s ex must be unique and <=6' % fid)

    extra = disk_files - expected
    if extra:
        raise ValueError('extra shard files: %s' % sorted(extra)[:5])

    for pos, r in enumerate(rec):
        sid = 'i%d' % pos
        if sid not in sid2node:
            raise ValueError('rec[%d] sid %s missing from shards' % (pos, sid))
    for sid, fid in sid2fam.items():
        k = int(sid[1:])
        if k >= len(rec) or rec[k]['f'] != fid:
            raise ValueError('dangling sid %s -> %s' % (sid, fid))
    for fid, meta in fam.items():
        for sid in meta['ex']:
            if sid2fam.get(sid) != fid:
                raise ValueError('fam %s ex sid %s not a member' % (fid, sid))

    return {
        'index': index,
        'index_path': index_path,
        'shards_dir': shards_dir,
        'sid2node': sid2node,
    }


def select_new_sources(base, incoming):
    """Return (new_sources, report rows) for incoming list of objects."""
    index = base['index']
    base_keys = set()
    for r in index['rec']:
        node = base['sid2node']['i%d' % r['i']]
        base_keys.add(identity_key(node.get('bookSourceUrl'),
                                   node.get('bookSourceType')))

    new_sources = []
    rows = []
    seen_new = {}
    skipped = 0
    for pos, item in enumerate(incoming):
        if not isinstance(item, dict):
            raise ValueError('incoming source #%d is not an object' % pos)
        url = item.get('bookSourceUrl')
        if not isinstance(url, str) or not url.strip():
            raise ValueError('incoming source #%d bad bookSourceUrl %r'
                             % (pos, url))
        name = item.get('bookSourceName')
        if not isinstance(name, str) or not name.strip():
            raise ValueError('incoming source #%d bad bookSourceName %r'
                             % (pos, name))
        stype = item.get('bookSourceType')
        if stype is not None:
            ok = isinstance(stype, int) and not isinstance(stype, bool) \
                and 0 <= stype <= 4
            if not ok and isinstance(stype, str):
                s = stype.strip()
                ok = s.isdigit() and 0 <= int(s) <= 4
            if not ok:
                raise ValueError(
                    'incoming source #%d bad bookSourceType %r'
                    % (pos, stype))
        key = identity_key(url, stype)
        row = {'key': key, 'inputIndex': pos,
               'name': item.get('bookSourceName')}
        if key in base_keys:
            row['action'] = 'skipped-existing'
            skipped += 1
            rows.append(row)
            continue
        if key in seen_new:
            prev = incoming[seen_new[key]]
            if json.dumps(prev, ensure_ascii=False, sort_keys=True) == \
                    json.dumps(item, ensure_ascii=False, sort_keys=True):
                row['action'] = 'skipped-identical-duplicate'
                skipped += 1
                rows.append(row)
                continue
            raise ValueError(
                'incoming identity key conflict at #%d and #%d: %s'
                % (seen_new[key], pos, key[:200]))
        seen_new[key] = pos
        row['action'] = 'added'
        rows.append(row)
        new_sources.append(item)
    return new_sources, rows, skipped


def _max_fid_num(fam):
    return max(int(fid[2:]) for fid in fam) if fam else 0


def _remap_shard_file(old_path, out_path, fid_map, sid_offset):
    obj = _read_json(old_path)
    old_fid = obj['f']
    new_fid_num = fid_map[old_fid]
    new_fid = 'f_%04d' % new_fid_num
    obj['f'] = new_fid
    obj['ids'] = ['i%d' % (int(sid[1:]) + sid_offset) for sid in obj['ids']]
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'))
    return os.path.getsize(out_path)


def _canon(path):
    """Case/symlink-insensitive canonical path for safe comparisons."""
    return os.path.normcase(os.path.realpath(path))


def _is_within(path, parent):
    return _canon(parent) in (_canon(path),) or \
        os.path.commonpath([_canon(path), _canon(parent)]) == _canon(parent)


def _check_path_safety(base_dir, src_path, out_dir, report_path):
    base_c = _canon(base_dir)
    out_c = _canon(out_dir)
    src_c = _canon(src_path)
    if out_c == base_c:
        raise ValueError('--out must differ from --base')
    if _is_within(out_dir, base_dir):
        raise ValueError('--out must not be inside --base')
    if out_c == src_c:
        raise ValueError('--out must differ from --src')
    if report_path:
        rep_c = _canon(report_path)
        if rep_c == src_c:
            raise ValueError('--report must differ from --src')
        if _is_within(report_path, base_dir):
            raise ValueError('--report must not be inside --base')
        if _is_within(report_path, out_dir):
            raise ValueError('--report must not be inside --out')
        if rep_c == out_c:
            raise ValueError('--report must differ from --out')


def append_corpus(base_dir, src_path, out_dir, report_path=None,
                  merge_source='unknown', strict_out=True):
    base_dir = os.path.abspath(base_dir)
    out_dir = os.path.abspath(out_dir)
    src_path = os.path.abspath(src_path)
    if report_path:
        report_path = os.path.abspath(report_path)
    # all guards run before anything is written
    _check_path_safety(base_dir, src_path, out_dir, report_path)
    if os.path.exists(out_dir):
        if strict_out:
            raise ValueError('--out already exists: %s' % out_dir)
        if os.path.isdir(out_dir) and os.listdir(out_dir):
            raise ValueError('--out is not empty: %s' % out_dir)

    base = load_and_validate_base(base_dir)
    index = base['index']

    with open(src_path, 'r', encoding='utf-8-sig') as f:
        incoming = json.load(f)
    if not isinstance(incoming, list):
        raise ValueError('incoming source file is not a JSON array')

    new_sources, rows, skipped = select_new_sources(base, incoming)
    base_n = index['n']
    max_fid = _max_fid_num(index['fam'])

    gen_date = date.today().isoformat()
    tmpdir = tempfile.mkdtemp(prefix='append_corpus_')
    try:
        # 1) run the unmodified builder on the new-only subset
        cand_src = os.path.join(tmpdir, 'new-sources.json')
        cand_out = os.path.join(tmpdir, 'cand')
        with open(cand_src, 'w', encoding='utf-8') as f:
            json.dump(new_sources, f, ensure_ascii=False)
        builder = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               'build_corpus.py')
        proc = subprocess.run(
            [sys.executable, builder, '--src', cand_src, '--out', cand_out],
            capture_output=True, text=True, encoding='utf-8')
        if proc.returncode != 0:
            raise RuntimeError('build_corpus failed:\n%s\n%s'
                               % (proc.stdout, proc.stderr))
        cand = load_and_validate_base(cand_out)
        cand_index = cand['index']

        # 2) prepare output tree; any failure below removes the out dir
        out_created = not os.path.exists(out_dir)
        try:
            shards_out = os.path.join(out_dir, 'shards')
            os.makedirs(shards_out, exist_ok=True)
            shutil.copy2(base['index_path'], os.path.join(out_dir,
                                                        'index.json'))
            for fn in os.listdir(base['shards_dir']):
                shutil.copy2(os.path.join(base['shards_dir'], fn),
                             os.path.join(shards_out, fn))

            fid_map = {}
            for idx_pos, old_fid in enumerate(
                    sorted(cand_index['fam'].keys()), start=1):
                fid_map[old_fid] = max_fid + idx_pos

            new_fam_meta = {}
            for fn in os.listdir(cand['shards_dir']):
                old_fid = fn.split('-')[0].split('.')[0]
                new_num = fid_map[old_fid]
                new_fn = fn.replace(old_fid, 'f_%04d' % new_num, 1)
                size = _remap_shard_file(
                    os.path.join(cand['shards_dir'], fn),
                    os.path.join(shards_out, new_fn),
                    fid_map, base_n)
                nfid = 'f_%04d' % new_num
                meta = new_fam_meta.setdefault(nfid, {'s': 0})
                meta['s'] += size
            for old_fid, meta in cand_index['fam'].items():
                nfid = 'f_%04d' % fid_map[old_fid]
                nm = new_fam_meta[nfid]
                nm.update({
                    't': meta['t'],
                    'm': meta['m'],
                    'c': meta['c'],
                    'ex': ['i%d' % (int(s[1:]) + base_n) for s in meta['ex']],
                })

            new_recs = []
            for r in cand_index['rec']:
                new_recs.append({
                    'i': r['i'] + base_n,
                    'd': r['d'],
                    'n': r['n'],
                    't': r['t'],
                    'f': 'f_%04d' % fid_map[r['f']],
                    'g': r['g'],
                })

            # report rows: added -> new sid; skipped-existing -> base sids
            node2newi = {}
            for r in cand_index['rec']:
                node = cand['sid2node']['i%d' % r['i']]
                key = identity_key(node.get('bookSourceUrl'),
                                   node.get('bookSourceType'))
                node2newi[key] = r['i'] + base_n
            for row in rows:
                if row['action'] == 'added':
                    row['newI'] = node2newi[row['key']]
                    row['sid'] = 'i%d' % row['newI']
            base_key_sids = {}
            for r in index['rec']:
                node = base['sid2node']['i%d' % r['i']]
                key = identity_key(node.get('bookSourceUrl'),
                                   node.get('bookSourceType'))
                base_key_sids.setdefault(key, []).append('i%d' % r['i'])
            for row in rows:
                if row['action'] == 'skipped-existing':
                    row['existingSids'] = base_key_sids[row['key']]

            if new_sources:
                out_index = dict(index)
                out_index['n'] = base_n + len(new_sources)
                out_index['gen'] = gen_date
                out_index['rec'] = index['rec'] + new_recs
                out_index['fam'] = dict(index['fam'])
                out_index['fam'].update(new_fam_meta)
                prov = list(index.get('provenance') or [])
                prov.append({
                    'source': merge_source,
                    'mergePolicy': 'keep-base-append-only',
                    'baseIndexSha256': _sha256_file(base['index_path']),
                    'sourceSha256': _sha256_file(src_path),
                    'baseSources': base_n,
                    'incomingSources': len(incoming),
                    'skippedExisting': skipped,
                    'addedSources': len(new_sources),
                    'firstSourceIndex': base_n,
                    'addedFamilies': len(new_fam_meta),
                    'generatedDate': gen_date,
                })
                out_index['provenance'] = prov
                with open(os.path.join(out_dir, 'index.json'), 'w',
                          encoding='utf-8') as f:
                    json.dump(out_index, f, ensure_ascii=False,
                              separators=(',', ':'))
            # else: nothing added -> index.json stays the byte copy of base

            # 3) validate the produced output before reporting success
            check = load_and_validate_base(out_dir)
            if check['index']['n'] != base_n + len(new_sources):
                raise AssertionError('output n mismatch')
            out_index = check['index']

            stats = {
                'baseSources': base_n,
                'incomingSources': len(incoming),
                'skippedExisting': skipped,
                'addedSources': len(new_sources),
                'firstSourceIndex': base_n,
                'addedFamilies': len(new_fam_meta),
                'totalSources': out_index['n'],
                'totalFamilies': len(out_index['fam']),
                'generatedDate': gen_date,
            }
            if report_path:
                report = {'stats': stats, 'sources': rows}
                with open(report_path, 'w', encoding='utf-8') as f:
                    json.dump(report, f, ensure_ascii=False, indent=1)
        except Exception:
            if out_created:
                shutil.rmtree(out_dir, ignore_errors=True)
            raise
        print(json.dumps(stats, indent=2, ensure_ascii=False))
        return stats
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def main():
    p = argparse.ArgumentParser(
        description='Append new sources to an existing corpus (base wins).')
    p.add_argument('--base', required=True,
                   help='existing corpus dir (index.json + shards/)')
    p.add_argument('--src', required=True,
                   help='merged bookSource JSON export (array of objects)')
    p.add_argument('--out', required=True,
                   help='new output dir; must not exist or equal base')
    p.add_argument('--report', default=None,
                   help='optional audit report JSON path (not shipped)')
    p.add_argument('--merge-source', default='unknown',
                   help='public name of the incoming snapshot for provenance')
    args = p.parse_args()
    try:
        append_corpus(args.base, args.src, args.out, args.report,
                      merge_source=args.merge_source)
    except Exception as e:
        print('append_corpus failed: %s' % e, file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
