#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""stdlib unittest coverage for append_corpus.py (no third-party deps)."""

import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import append_corpus as ac  # noqa: E402


def make_source(name, url, stype=0, extra=None):
    s = {
        'bookSourceName': name,
        'bookSourceUrl': url,
        'bookSourceType': stype,
        'ruleSearch': {'bookList': '$.list'},
        'ruleBookInfo': {'init': '$.x', 'name': '$.n'},
        'ruleToc': {'chapterList': '$.c'},
        'ruleContent': {'content': '$.t'},
    }
    if extra:
        s.update(extra)
    return s


def build_base(sources, out):
    """Build a minimal corpus dir with build_corpus's own writer."""
    import build_corpus as bc
    src = os.path.join(out, '_src.json')
    with open(src, 'w', encoding='utf-8') as f:
        json.dump(sources, f, ensure_ascii=False)
    corpus = os.path.join(out, 'base')
    os.makedirs(os.path.join(corpus, 'shards'))
    # call the module main via argv
    argv_bak = sys.argv
    try:
        sys.argv = ['build_corpus.py', '--src', src, '--out', corpus]
        bc.main()
    finally:
        sys.argv = argv_bak
    return corpus


def read_index(corpus):
    with open(os.path.join(corpus, 'index.json'), encoding='utf-8') as f:
        return json.load(f)


def shard_bytes_map(corpus):
    d = os.path.join(corpus, 'shards')
    out = {}
    for fn in os.listdir(d):
        with open(os.path.join(d, fn), 'rb') as f:
            out[fn] = f.read()
    return out


def sid_nodes(corpus):
    d = os.path.join(corpus, 'shards')
    out = {}
    for fn in os.listdir(d):
        with open(os.path.join(d, fn), encoding='utf-8') as f:
            o = json.load(f)
        for sid, node in zip(o['ids'], o['n']):
            out[sid] = node
    return out


class IdentityKeyTest(unittest.TestCase):
    def test_scheme_host_case_and_default_port(self):
        k1 = ac.identity_key('HTTP://Example.COM:80/a?x=1', 0)
        k2 = ac.identity_key('http://example.com/a?x=1', 0)
        self.assertEqual(k1, k2)
        k3 = ac.identity_key('https://EXAMPLE.com:443/', 0)
        self.assertEqual(k3, '0|https://example.com')
        # non-default port preserved
        self.assertEqual(
            ac.identity_key('http://example.com:8080', 0),
            '0|http://example.com:8080')
        # https:443 vs https no port equal; http:443 differs
        self.assertNotEqual(
            ac.identity_key('http://example.com:443', 0),
            '0|http://example.com')

    def test_root_path_and_empty_fragment(self):
        self.assertEqual(ac.identity_key('http://a.com/', 0),
                         ac.identity_key('http://a.com', 0))
        self.assertEqual(ac.identity_key('http://a.com/#', 0),
                         ac.identity_key('http://a.com/', 0))
        # bare '?' in query preserved
        self.assertEqual(ac.identity_key('http://a.com/?', 0),
                         '0|http://a.com?')
        # non-empty fragment preserved
        self.assertNotEqual(ac.identity_key('http://a.com/#x', 0),
                            ac.identity_key('http://a.com/', 0))

    def test_verbatim_parts(self):
        # www kept, userinfo kept, path case kept
        self.assertNotEqual(ac.identity_key('http://www.a.com/x', 0),
                            ac.identity_key('http://a.com/x', 0))
        self.assertIn('user:pw@', ac.identity_key(
            'http://user:pw@a.com/Path', 0))
        self.assertIn('/Path', ac.identity_key('http://a.com/Path', 0))
        # http vs https differ
        self.assertNotEqual(ac.identity_key('http://a.com', 0),
                            ac.identity_key('https://a.com', 0))
        # type prefix
        self.assertTrue(ac.identity_key('http://a.com', 1).startswith('1|'))
        self.assertNotEqual(ac.identity_key('http://a.com', 0),
                            ac.identity_key('http://a.com', 1))
        # non-http scheme and scheme-less ids kept verbatim
        self.assertEqual(ac.identity_key('ftp://a.com/x', 0), '0|ftp://a.com/x')
        self.assertEqual(ac.identity_key('yck:abc123', 0), '0|yck:abc123')
        self.assertEqual(ac.identity_key('  ', 0), '0|')
        # IPv6 host with port
        self.assertEqual(
            ac.identity_key('http://[::1]:8080/p', 0),
            '0|http://[::1]:8080/p')
        self.assertEqual(
            ac.identity_key('http://[::1]:80/p', 0),
            '0|http://[::1]/p')


class BaseValidationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.base = build_base([
            make_source('A', 'http://a.com/'),
            make_source('B', 'http://b.com/x'),
        ], self.tmp)

    def test_valid_base_passes(self):
        base = ac.load_and_validate_base(self.base)
        self.assertEqual(base['index']['n'], 2)

    def test_reject_n_mismatch(self):
        idx = read_index(self.base)
        idx['n'] = 99
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_dangling_fam(self):
        idx = read_index(self.base)
        idx['rec'][0]['f'] = 'f_9999'
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_ids_len_mismatch(self):
        d = os.path.join(self.base, 'shards')
        fn = os.listdir(d)[0]
        p = os.path.join(d, fn)
        with open(p, encoding='utf-8') as f:
            o = json.load(f)
        o['ids'].append('i9999')
        # keep s consistent so we specifically trip the len check
        idx = read_index(self.base)
        fid = o['f']
        with open(p, 'w', encoding='utf-8') as f:
            json.dump(o, f, ensure_ascii=False, separators=(',', ':'))
        idx['fam'][fid]['s'] = os.path.getsize(p)
        idx['fam'][fid]['c'] = len(o['ids'])
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_extra_shard(self):
        with open(os.path.join(self.base, 'shards', 'junk.json'), 'w') as f:
            f.write('{}')
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_both_single_and_parts(self):
        d = os.path.join(self.base, 'shards')
        fn = next(f for f in os.listdir(d) if f.endswith('.json')
                  and '-' not in f)
        fid = fn[:-5]
        with open(os.path.join(d, fn), encoding='utf-8') as f:
            o = json.load(f)
        o['p'] = 0
        with open(os.path.join(d, fid + '-0.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(o, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def _rewrite_shard(self, mutate):
        """Mutate the single shard of the 2-source base + keep index s/c
        consistent so only the targeted check trips."""
        d = os.path.join(self.base, 'shards')
        fn = next(f for f in os.listdir(d) if '-' not in f)
        p = os.path.join(d, fn)
        with open(p, encoding='utf-8') as f:
            o = json.load(f)
        mutate(o)
        with open(p, 'w', encoding='utf-8') as f:
            json.dump(o, f, ensure_ascii=False, separators=(',', ':'))
        idx = read_index(self.base)
        idx['fam'][o['f']]['s'] = os.path.getsize(p)
        idx['fam'][o['f']]['c'] = len(o['ids'])
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        return o

    def test_reject_rec_sid_missing_from_shards(self):
        # drop i0 from ids but keep fam.c/s consistent -> must still fail
        d = os.path.join(self.base, 'shards')
        fn = next(f for f in os.listdir(d) if '-' not in f)
        p = os.path.join(d, fn)
        with open(p, encoding='utf-8') as f:
            o = json.load(f)
        pairs = [(s, nd) for s, nd in zip(o['ids'], o['n']) if s != 'i0']
        o['ids'] = [s for s, _ in pairs]
        o['n'] = [nd for _, nd in pairs]
        with open(p, 'w', encoding='utf-8') as f:
            json.dump(o, f, ensure_ascii=False, separators=(',', ':'))
        idx = read_index(self.base)
        idx['fam'][o['f']]['s'] = os.path.getsize(p)
        idx['fam'][o['f']]['c'] = len(o['ids'])
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_non_dict_node(self):
        def mut(o):
            o['n'][0] = 'not-an-object'
        self._rewrite_shard(mut)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_dup_ex(self):
        idx = read_index(self.base)
        fid = next(iter(idx['fam']))
        meta = idx['fam'][fid]
        meta['ex'] = [meta['ex'][0], meta['ex'][0]]
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_ex_too_many(self):
        idx = read_index(self.base)
        fid = next(iter(idx['fam']))
        meta = idx['fam'][fid]
        meta['ex'] = meta['ex'] + ['i0', 'i1'] + \
            ['i%d' % i for i in range(7)]
        meta['ex'] = list(dict.fromkeys(meta['ex']))[:7]
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)

    def test_reject_noncontiguous_parts(self):
        # craft a multi-part family with a gap: f_0001-0.json + f_0001-2.json
        d = os.path.join(self.base, 'shards')
        fn = next(f for f in os.listdir(d) if '-' not in f)
        fid = fn[:-5]
        with open(os.path.join(d, fn), encoding='utf-8') as f:
            o = json.load(f)
        ids, nodes = o['ids'], o['n']
        # part 0 keeps first member, part 2 keeps the rest (gap at p=1)
        for pnum, sel in ((0, ids[:1]), (2, ids[1:])):
            part = {'f': fid, 'p': pnum,
                    'ids': sel,
                    'n': [nodes[ids.index(s)] for s in sel]}
            with open(os.path.join(d, '%s-%d.json' % (fid, pnum)), 'w',
                      encoding='utf-8') as f:
                json.dump(part, f, ensure_ascii=False,
                          separators=(',', ':'))
        os.remove(os.path.join(d, fn))
        idx = read_index(self.base)
        idx['fam'][fid]['s'] = sum(
            os.path.getsize(os.path.join(d, x))
            for x in ('%s-0.json' % fid, '%s-2.json' % fid))
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        with self.assertRaises(ValueError):
            ac.load_and_validate_base(self.base)


class PathSafetySemanticsTest(unittest.TestCase):
    """路径守卫的纯语义锁：只算路径，不建目录、不写文件、不碰 /ROOT。

    平台事实：`os.path.normcase` 在 POSIX 上是恒等函数（不折叠大小写），在 Windows 上
    才 lower + 反斜杠化。所以「大小写别名」只有在 normcase 真的折叠时才成立，不能无条件
    断言；POSIX 下真正等价的只有「同一路径的不同写法」（`.`/`..`/重复分隔符），
    而大小写不同的路径在 Linux 上就是**不同路径**，生产代码也不得 lower() 掩盖这一点。
    """

    def setUp(self):
        # 纯字符串路径，不创建任何目录
        self.base = os.path.join(os.sep, 'srv', 'corpus', 'base')
        self.src = os.path.join(os.sep, 'srv', 'corpus', 'incoming.json')
        self.out = os.path.join(os.sep, 'srv', 'out')
        self.report = os.path.join(os.sep, 'srv', 'reports', 'r.json')

    def test_canon_is_normcase_realpath(self):
        self.assertEqual(ac._canon(self.base),
                         os.path.normcase(os.path.realpath(self.base)))

    def test_equivalent_spellings_are_rejected(self):
        # 同一路径的写法差异（. / .. / 重复分隔符）在任何平台都等价，必须拒绝
        spellings = [
            self.base + os.sep + '.',
            self.base + os.sep + 'sub' + os.sep + '..',
            os.path.join(os.sep, 'srv', 'corpus', '.', 'base'),
            self.base.replace(os.sep, os.sep + os.sep, 1),
        ]
        for out in spellings:
            with self.assertRaises(ValueError, msg='out=%r' % out):
                ac._check_path_safety(self.base, self.src, out, None)

    def test_report_equivalent_spelling_inside_base_rejected(self):
        report = os.path.join(self.base, 'sub', '..', 'report.json')
        with self.assertRaises(ValueError):
            ac._check_path_safety(self.base, self.src, self.out, report)

    def test_case_alias_follows_normcase_semantics(self):
        # 大小写别名：仅当本平台 normcase 真的折叠大小写（Windows）时才等价。
        # 用纯路径判定，不依赖任何大写目录真实存在。
        upper_out = os.path.join(self.base.upper(), 'o6')
        folds_case = os.path.normcase('A') == os.path.normcase('a')
        if folds_case:
            with self.assertRaises(ValueError):
                ac._check_path_safety(self.base, self.src, upper_out, None)
        else:
            # POSIX：大小写不同 = 不同路径，守卫放行（真实 Linux 语义，不 lower()）
            self.assertNotEqual(os.path.normcase(upper_out), os.path.normcase(self.base))
            ac._check_path_safety(self.base, self.src, upper_out, None)

    def test_windows_normcase_semantics_are_mocked_not_assumed(self):
        # 显式模拟 Windows 路径语义（不依赖运行平台、不写盘）：normcase 折叠大小写后
        # 大写别名必须被识别为「在 base 内」而拒绝。
        # POSIX 上 `C:\...` 不是绝对路径（realpath 会拼 cwd、commonpath 直接返回 ''），
        # 所以这里把被测语义收敛到「normcase 折叠大小写」一条：realpath 恒等，路径用 POSIX
        # 绝对写法，只有大小写不同——正是原 Windows 断言想验证的那一点。
        def win_normcase(path):
            return path.lower()

        real_normcase, real_realpath = os.path.normcase, os.path.realpath
        os.path.normcase, os.path.realpath = win_normcase, (lambda p: p)
        try:
            base = os.path.join(os.sep, 'srv', 'Corpus', 'Base')
            src = os.path.join(os.sep, 'srv', 'Corpus', 'incoming.json')
            self.assertEqual(ac._canon(base), base.lower())
            with self.assertRaises(ValueError):
                ac._check_path_safety(base, src, os.path.join(base.lower(), 'o6'), None)
            with self.assertRaises(ValueError):
                ac._check_path_safety(base, src, base.upper(), None)
            # 真正不同的路径仍放行
            ac._check_path_safety(base, src, os.path.join(os.sep, 'srv', 'Corpus', 'Out'), None)
        finally:
            os.path.normcase, os.path.realpath = real_normcase, real_realpath

    def test_distinct_paths_pass_on_posix(self):
        # POSIX「不同路径」纯校验：大小写不同的兄弟目录、完全不同的目录都放行
        for out in (os.path.join(os.sep, 'srv', 'corpus', 'BASE'),
                    os.path.join(os.sep, 'srv', 'corpus', 'base2'),
                    os.path.join(os.sep, 'srv', 'out')):
            ac._check_path_safety(self.base, self.src, out, None)
            ac._check_path_safety(self.base, self.src, self.out, self.report)

    def test_guards_are_pure_no_dirs_created(self):
        # 守卫只算路径：对不存在的目录跑完全部拒绝分支后，父目录仍不存在
        ghost = os.path.join(tempfile.gettempdir(), 'append_corpus_ghost_%d' % os.getpid())
        self.assertFalse(os.path.exists(ghost))
        with self.assertRaises(ValueError):
            ac._check_path_safety(ghost, self.src, os.path.join(ghost, 'o'), None)
        with self.assertRaises(ValueError):
            ac._check_path_safety(self.base, self.src, self.out,
                                  os.path.join(self.base, 'report.json'))
        self.assertFalse(os.path.exists(ghost))
        self.assertFalse(os.path.exists(self.base))


class AppendFlowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        # two versions of the SAME identity already in base + one other
        self.base = build_base([
            make_source('A-v1', 'http://a.com/'),
            make_source('A-v2', 'http://a.com/'),   # old multi-version kept
            make_source('B', 'http://b.com/x'),
        ], self.tmp)
        self.base_idx = read_index(self.base)
        self.base_shards = shard_bytes_map(self.base)

    def run_append(self, incoming, out_name='out', **kw):
        src = os.path.join(self.tmp, 'incoming.json')
        with open(src, 'w', encoding='utf-8') as f:
            json.dump(incoming, f, ensure_ascii=False)
        out = os.path.join(self.tmp, out_name)
        report = os.path.join(self.tmp, out_name + '-report.json')
        stats = ac.append_corpus(self.base, src, out, report,
                                 merge_source='test-snap', **kw)
        return out, report, stats

    def test_skip_existing_and_add_new(self):
        incoming = [
            make_source('A-new-version', 'HTTP://a.com:80/'),  # same key
            make_source('C', 'http://c.com/new'),
        ]
        out, report, stats = self.run_append(incoming)
        self.assertEqual(stats['skippedExisting'], 1)
        self.assertEqual(stats['addedSources'], 1)
        idx = read_index(out)
        self.assertEqual(idx['n'], 4)
        # old recs/fam untouched
        self.assertEqual(idx['rec'][:3], self.base_idx['rec'])
        for fid, meta in self.base_idx['fam'].items():
            self.assertEqual(idx['fam'][fid], meta)
        # old shard bytes identical
        new_shards = shard_bytes_map(out)
        for fn, b in self.base_shards.items():
            self.assertEqual(new_shards[fn], b)
        # new source readable via new sid
        nodes = sid_nodes(out)
        self.assertEqual(nodes['i3']['bookSourceName'], 'C')
        self.assertEqual(idx['rec'][3]['i'], 3)
        self.assertTrue(idx['rec'][3]['f'].startswith('f_'))
        # new fid beyond old max
        old_max = max(int(f[2:]) for f in self.base_idx['fam'])
        new_fids = {int(f[2:]) for f in idx['fam']} - set(
            int(f[2:]) for f in self.base_idx['fam'])
        self.assertTrue(all(n > old_max for n in new_fids))
        # report
        with open(report, encoding='utf-8') as f:
            rep = json.load(f)
        acts = [r['action'] for r in rep['sources']]
        self.assertEqual(acts, ['skipped-existing', 'added'])
        skipped_row = rep['sources'][0]
        self.assertEqual(len(skipped_row['existingSids']), 2)
        # provenance
        prov = idx['provenance']
        self.assertEqual(len(prov), 1)
        p = prov[-1]
        self.assertEqual(p['source'], 'test-snap')
        self.assertEqual(p['mergePolicy'], 'keep-base-append-only')
        self.assertEqual(p['baseSources'], 3)
        self.assertEqual(p['incomingSources'], 2)
        self.assertEqual(p['skippedExisting'], 1)
        self.assertEqual(p['addedSources'], 1)
        self.assertEqual(p['firstSourceIndex'], 3)
        self.assertEqual(len(p['baseIndexSha256']), 64)
        self.assertEqual(len(p['sourceSha256']), 64)

    def test_output_validates_and_readback(self):
        incoming = [make_source('C', 'http://c.com/'),
                    make_source('D', 'http://d.com/')]
        out, _, _ = self.run_append(incoming)
        check = ac.load_and_validate_base(out)
        self.assertEqual(check['index']['n'], 5)
        nodes = sid_nodes(out)
        names = {nodes['i%d' % i]['bookSourceName'] for i in range(5)}
        self.assertIn('C', names)
        self.assertIn('D', names)

    def test_out_must_not_exist_or_be_base(self):
        src = os.path.join(self.tmp, 'inc.json')
        with open(src, 'w') as f:
            json.dump([], f)
        with self.assertRaises(ValueError):
            ac.append_corpus(self.base, src, self.base)
        existing = os.path.join(self.tmp, 'existing')
        os.makedirs(existing)
        with self.assertRaises(ValueError):
            ac.append_corpus(self.base, src, existing)

    def test_idempotent_reapply(self):
        incoming = [make_source('C', 'http://c.com/')]
        out1, _, stats1 = self.run_append(incoming, 'out1')
        self.assertEqual(stats1['addedSources'], 1)
        idx1 = read_index(out1)
        shards1 = shard_bytes_map(out1)
        # second run: same src against out1 -> adds 0, bytes identical
        src = os.path.join(self.tmp, 'incoming.json')
        out2 = os.path.join(self.tmp, 'out2')
        stats2 = ac.append_corpus(out1, src, out2,
                                  merge_source='test-snap')
        self.assertEqual(stats2['addedSources'], 0)
        self.assertEqual(stats2['skippedExisting'], 1)
        idx2 = read_index(out2)
        shards2 = shard_bytes_map(out2)
        self.assertEqual(shards1, shards2)
        # rec/fam/provenance must be a byte-stable clone
        self.assertEqual(idx1['rec'], idx2['rec'])
        self.assertEqual(idx1['fam'], idx2['fam'])
        self.assertEqual(idx1.get('provenance'), idx2.get('provenance'))
        self.assertEqual(idx1['n'], idx2['n'])
        self.assertEqual(idx1['gen'], idx2['gen'])

    def test_incoming_conflict_errors(self):
        incoming = [
            make_source('C1', 'http://c.com/'),
            make_source('C2', 'http://c.com/', extra={'weight': 5}),
        ]
        src = os.path.join(self.tmp, 'inc2.json')
        with open(src, 'w', encoding='utf-8') as f:
            json.dump(incoming, f)
        out = os.path.join(self.tmp, 'out-conflict')
        with self.assertRaises(ValueError):
            ac.append_corpus(self.base, src, out)
        self.assertFalse(os.path.exists(out))

    def test_incoming_identical_duplicate_collapses(self):
        s = make_source('C', 'http://c.com/')
        src = os.path.join(self.tmp, 'inc3.json')
        with open(src, 'w', encoding='utf-8') as f:
            json.dump([s, dict(s)], f)
        out = os.path.join(self.tmp, 'out-dupe')
        stats = ac.append_corpus(self.base, src, out)
        self.assertEqual(stats['addedSources'], 1)
        self.assertEqual(stats['skippedExisting'], 1)

    def test_bad_incoming_rejected(self):
        src = os.path.join(self.tmp, 'inc4.json')
        bad_items = [
            [['not-an-object']],
            [{'bookSourceName': 'noUrl'}],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceUrl': 123})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceUrl': '  '})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceName': ''})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceName': ['a']})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceType': True})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceType': 9})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceType': 'abc'})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceType': [0]})],
            [make_source('X', 'http://x.com/', extra={
                'bookSourceType': {'t': 0}})],
        ]
        for i, payload in enumerate(bad_items):
            with open(src, 'w') as f:
                json.dump(payload, f)
            with self.assertRaises(ValueError, msg='payload %d' % i):
                ac.append_corpus(self.base, src,
                                 os.path.join(self.tmp, 'out-bad%d' % i))
        # valid type as string '2' accepted
        good = make_source('X', 'http://x.com/', extra={'bookSourceType': '2'})
        with open(src, 'w') as f:
            json.dump([good], f)
        stats = ac.append_corpus(self.base, src,
                                 os.path.join(self.tmp, 'out-goodtype'))
        self.assertEqual(stats['addedSources'], 1)

    def test_path_safety_report_and_out(self):
        src = os.path.join(self.tmp, 'inc-safe.json')
        with open(src, 'w') as f:
            json.dump([make_source('C', 'http://c.com/')], f)
        # report inside base -> refuse before writing
        with self.assertRaises(ValueError):
            ac.append_corpus(
                self.base, src, os.path.join(self.tmp, 'o1'),
                os.path.join(self.base, 'report.json'))
        # report == base index path
        with self.assertRaises(ValueError):
            ac.append_corpus(
                self.base, src, os.path.join(self.tmp, 'o2'),
                os.path.join(self.base, 'index.json'))
        # report == src
        with self.assertRaises(ValueError):
            ac.append_corpus(
                self.base, src, os.path.join(self.tmp, 'o3'), src)
        # report inside out
        out4 = os.path.join(self.tmp, 'o4')
        with self.assertRaises(ValueError):
            ac.append_corpus(
                self.base, src, out4,
                os.path.join(out4, 'r.json'))
        # out inside base
        with self.assertRaises(ValueError):
            ac.append_corpus(
                self.base, src, os.path.join(self.base, 'o5'))
        # none of the refused runs created an out dir
        for name in ('o1', 'o2', 'o3', 'o4', 'o5'):
            self.assertFalse(os.path.exists(os.path.join(self.tmp, name)))
            self.assertFalse(os.path.exists(os.path.join(self.base, name)))
        self.assertFalse(os.path.exists(
            os.path.join(self.base, 'report.json')))

    def test_pretty_base_index_byte_cloned_when_no_add(self):
        # rewrite base index with pretty formatting; re-apply same src
        # (all incoming already in base) -> index.json must be byte clone
        pretty = json.dumps(self.base_idx, ensure_ascii=False, indent=2)
        with open(os.path.join(self.base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            f.write(pretty)
        with open(os.path.join(self.base, 'index.json'), 'rb') as f:
            before = f.read()
        src = os.path.join(self.tmp, 'inc-pretty.json')
        with open(src, 'w') as f:
            json.dump([make_source('A-v3', 'http://a.com/')], f)
        out = os.path.join(self.tmp, 'out-pretty')
        stats = ac.append_corpus(self.base, src, out)
        self.assertEqual(stats['addedSources'], 0)
        with open(os.path.join(out, 'index.json'), 'rb') as f:
            self.assertEqual(f.read(), before)

    def test_failure_does_not_touch_base(self):
        with open(os.path.join(self.base, 'index.json'), 'rb') as f:
            before_idx = f.read()
        src = os.path.join(self.tmp, 'inc5.json')
        with open(src, 'w') as f:
            f.write('{invalid json')
        with self.assertRaises(Exception):
            ac.append_corpus(self.base, src,
                             os.path.join(self.tmp, 'out-fail'))
        with open(os.path.join(self.base, 'index.json'), 'rb') as f:
            self.assertEqual(f.read(), before_idx)
        self.assertFalse(os.path.exists(os.path.join(self.tmp, 'out-fail')))


if __name__ == '__main__':
    unittest.main()
