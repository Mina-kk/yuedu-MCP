#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""stdlib unittest coverage for reconcile_corpus.py (no third-party deps)."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
import append_corpus as ac  # noqa: E402
import build_corpus as bc  # noqa: E402
import reconcile_corpus as rc  # noqa: E402

NOW_MS = 1_760_000_000_000  # fixed "now" for deterministic time tests
PAST_S = 1_700_000_000      # 2023-11, seconds
PAST_MS = PAST_S * 1000
PAST2_MS = 1_750_000_000_000  # newer ms
FUTURE_MS = NOW_MS + 10_000_000


def _chain(content='$.t', toc=None):
    """A complete, substantive reading chain for a type-0 source."""
    t = {'chapterList': '$.c', 'chapterName': '$.n', 'chapterUrl': '$.u'}
    if toc:
        t.update(toc)
    return {
        'ruleSearch': {'bookList': '$.list', 'name': '$.n'},
        'ruleBookInfo': {'init': '$.x', 'name': '$.n'},
        'ruleToc': t,
        'ruleContent': {'content': content},
    }


def make_source(name, url, stype=0, rules=None, extra=None):
    s = {
        'bookSourceName': name,
        'bookSourceUrl': url,
        'bookSourceType': stype,
    }
    s.update(rules if rules is not None else _chain())
    if extra:
        s.update(extra)
    return s


def build_base(sources, out, prov=False):
    src = os.path.join(out, '_src.json')
    with open(src, 'w', encoding='utf-8') as f:
        json.dump(sources, f, ensure_ascii=False)
    corpus = os.path.join(out, 'base')
    os.makedirs(os.path.join(corpus, 'shards'))
    argv_bak = sys.argv
    try:
        sys.argv = ['build_corpus.py', '--src', src, '--out', corpus]
        bc.main()
    finally:
        sys.argv = argv_bak
    if prov:
        idx_path = os.path.join(corpus, 'index.json')
        with open(idx_path, encoding='utf-8') as f:
            idx = json.load(f)
        idx['provenance'] = [{'firstSourceIndex': idx['n']}]
        with open(idx_path, 'w', encoding='utf-8') as f:
            json.dump(idx, f, ensure_ascii=False, separators=(',', ':'))
    return corpus


def read_index(corpus):
    with open(os.path.join(corpus, 'index.json'), encoding='utf-8') as f:
        return json.load(f)


def sid_nodes(corpus):
    d = os.path.join(corpus, 'shards')
    out = {}
    for fn in os.listdir(d):
        with open(os.path.join(d, fn), encoding='utf-8') as f:
            o = json.load(f)
        for sid, node in zip(o['ids'], o['n']):
            out[sid] = node
    return out


def stage_names(corpus):
    nodes = sid_nodes(corpus)
    idx = read_index(corpus)
    return {nodes['i%d' % r['i']]['bookSourceName'] for r in idx['rec']}


class ReconcileBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def run_reconcile(self, base_sources, incoming, base_with_prov=True,
                      out_name='stage', **kw):
        base = build_base(base_sources, self.tmp,
                          prov=base_with_prov)
        src = os.path.join(self.tmp, 'incoming.json')
        with open(src, 'w', encoding='utf-8') as f:
            json.dump(incoming, f, ensure_ascii=False)
        stage = os.path.join(self.tmp, out_name)
        report = os.path.join(self.tmp, out_name + '-report.json')
        kw.setdefault('now_ms', NOW_MS)
        kw.setdefault('merge_source', 'test-snap')
        stats = rc.reconcile_corpus(base, src, stage, report, **kw)
        return base, src, stage, report, stats

    def read_report(self, report):
        with open(report, encoding='utf-8') as f:
            return json.load(f)

    def key_status(self, report, status):
        rep = self.read_report(report)
        return {k: v for k, v in rep['keys'].items()
                if v['status'] == status}


class DetectBaseNTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_provenance_first_source_index(self):
        base = build_base([make_source('A', 'http://a.com/'),
                           make_source('B', 'http://b.com/')], self.tmp)
        idx = read_index(base)
        idx['provenance'] = [{'firstSourceIndex': 1}]
        with open(os.path.join(base, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        self.assertEqual(rc.detect_base_n(idx), 1)

    def test_missing_provenance_requires_override(self):
        base = build_base([make_source('A', 'http://a.com/')], self.tmp)
        idx = read_index(base)
        with self.assertRaises(ValueError):
            rc.detect_base_n(idx)
        self.assertEqual(rc.detect_base_n(idx, override=1), 1)

    def test_override_mismatch_rejected(self):
        base = build_base([make_source('A', 'http://a.com/'),
                           make_source('B', 'http://b.com/')], self.tmp)
        idx = read_index(base)
        idx['provenance'] = [{'firstSourceIndex': 1}]
        with self.assertRaises(ValueError):
            rc.detect_base_n(idx, override=2)

    def test_rebuild_base_uses_all_records(self):
        # a rebuild output has no append-only boundary: every record is a
        # legitimate candidate, so base_n == n.
        idx = {'n': 3, 'provenance': [
            {'mergePolicy': 'global-dedupe-rebuild', 'baseSources': 3},
        ]}
        self.assertEqual(rc.detect_base_n(idx), 3)

    def test_rebuild_base_ignores_stale_first_source_index(self):
        # Regression: a rebuilt corpus whose history still carries the
        # pre-rebuild append-only firstSourceIndex (which may exceed the
        # rebuilt n) must NOT be treated as an active tail boundary.
        idx = {'n': 5, 'provenance': [
            {'mergePolicy': 'keep-base-append-only', 'firstSourceIndex': 26861},
            {'mergePolicy': 'global-dedupe-rebuild', 'baseSources': 5},
        ]}
        self.assertEqual(rc.detect_base_n(idx), 5)
        # an override that agrees with "the whole index" is accepted
        self.assertEqual(rc.detect_base_n(idx, override=5), 5)
        # a contradictory override is still refused
        with self.assertRaises(ValueError):
            rc.detect_base_n(idx, override=4)

    def test_append_only_boundary_after_rebuild_still_applies(self):
        # rebuild entry first, then a later append-only merge: the newest
        # boundary is the active one and is range-checked.
        idx = {'n': 10, 'provenance': [
            {'mergePolicy': 'global-dedupe-rebuild', 'baseSources': 4},
            {'mergePolicy': 'keep-base-append-only', 'firstSourceIndex': 7},
        ]}
        self.assertEqual(rc.detect_base_n(idx), 7)
        # out-of-range boundary in the active lineage is still rejected
        bad = {'n': 10, 'provenance': [
            {'mergePolicy': 'global-dedupe-rebuild', 'baseSources': 4},
            {'firstSourceIndex': 11},
        ]}
        with self.assertRaises(ValueError):
            rc.detect_base_n(bad)


class RuleTimeTest(unittest.TestCase):
    def test_seconds_ms_and_invalid(self):
        ms, flag = rc.parse_rule_time(PAST_S, NOW_MS)
        self.assertEqual((ms, flag), (PAST_MS, None))
        ms, flag = rc.parse_rule_time(str(PAST2_MS), NOW_MS)
        self.assertEqual((ms, flag), (PAST2_MS, None))
        self.assertEqual(rc.parse_rule_time(None, NOW_MS), (None, None))
        for bad in ('abc', '1.5', 'x100', {}, []):
            ms, flag = rc.parse_rule_time(bad, NOW_MS)
            self.assertIsNone(ms)
            self.assertIsNotNone(flag)
        for bad in (0, -5, '0'):
            ms, flag = rc.parse_rule_time(bad, NOW_MS)
            self.assertIsNone(ms)
            self.assertTrue(flag.startswith('non-positive'))
        ms, flag = rc.parse_rule_time(FUTURE_MS, NOW_MS)
        self.assertIsNone(ms)
        self.assertTrue(flag.startswith('future'))
        # sentinel 32500886400000 (year 2999) is a future value -> invalid
        ms, flag = rc.parse_rule_time(32500886400000, NOW_MS)
        self.assertIsNone(ms)
        self.assertTrue(flag.startswith('future'))
        # implausible small numbers rejected
        ms, flag = rc.parse_rule_time(20200101, NOW_MS)
        self.assertIsNone(ms)
        self.assertTrue(flag.startswith('implausible'))


class AdultMarkerTest(unittest.TestCase):
    def test_explicit_markers(self):
        f = rc.explicit_adult_marker
        self.assertEqual(f('xx🔞', '', 'a.com'), 'nsfw-tag')
        self.assertEqual(f('18禁小说', '', 'a.com'), 'nsfw-tag')
        self.assertEqual(f('NSFW hub', '', 'a.com'), 'nsfw-tag')
        self.assertEqual(f('普通', 'R18', 'a.com'), 'nsfw-tag')
        self.assertEqual(f('禁漫天堂', '', 'a.com'), 'nsfw-name')
        self.assertEqual(f('po18书库', '', 'a.com'), 'nsfw-name')
        self.assertEqual(f('', '', 'xx.po18.com'), 'nsfw-host')
        self.assertEqual(f('', '', 'haitang123.com'), 'nsfw-host')
        # ordinary names are NOT flagged (海棠 in a plain name)
        self.assertIsNone(f('海棠书屋', '', 'a.com'))
        self.assertIsNone(f('普通小说站', '', 'a.com'))
        self.assertIsNone(f('', '', 'invalid'))


class BucketDecisionTest(ReconcileBase):
    """End-to-end: synthetic base + incoming -> stage contents and audit."""

    def test_newer_incoming_wins_same_key(self):
        # same identity, different rules, both valid times -> newest wins
        old = make_source('A-old', 'http://a.com/', rules=_chain('$.old'),
                          extra={'lastUpdateTime': PAST_S})
        new = make_source('A-new', 'http://a.com/', rules=_chain('$.new'),
                          extra={'lastUpdateTime': PAST2_MS})
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        self.assertEqual(stats['identityKeys'], 1)
        self.assertEqual(stats['winnerKeys'], 1)
        self.assertEqual(stats['selectedSources'], 1)
        self.assertEqual(stats['timeDecidedKeys'], 1)
        self.assertEqual(stats['timeDecidedWinnerBySide']['incoming'], 1)
        nodes = sid_nodes(stage)
        self.assertEqual(nodes['i0']['ruleContent']['content'], '$.new')
        keys = self.key_status(report, 'winner')
        self.assertEqual(len(keys), 1)
        self.assertEqual(list(keys.values())[0]['winner']['side'],
                         'incoming')

    def test_older_base_can_win_when_it_is_newer(self):
        old = make_source('A-old', 'http://a.com/', rules=_chain('$.old'),
                          extra={'lastUpdateTime': PAST2_MS})
        new = make_source('A-new', 'http://a.com/', rules=_chain('$.new'),
                          extra={'lastUpdateTime': PAST_S})
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        nodes = sid_nodes(stage)
        self.assertEqual(nodes['i0']['ruleContent']['content'], '$.old')
        self.assertEqual(stats['timeDecidedWinnerBySide']['base'], 1)

    def test_both_untimed_different_rules_goes_review(self):
        old = make_source('A-old', 'http://a.com/', rules=_chain('$.a'))
        new = make_source('A-new', 'http://a.com/', rules=_chain('$.b'))
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        self.assertEqual(stats['winnerKeys'], 0)
        self.assertEqual(stats['reviewKeys'], 1)
        self.assertEqual(stats['selectedSources'], 0)
        self.assertEqual(stage_names(stage), set())
        rev = self.key_status(report, 'review')
        self.assertEqual(list(rev.values())[0]['reason'],
                         'version-conflict-no-valid-time')

    def test_one_side_untimed_goes_review(self):
        old = make_source('A-old', 'http://a.com/', rules=_chain('$.a'))
        new = make_source('A-new', 'http://a.com/', rules=_chain('$.b'),
                          extra={'lastUpdateTime': PAST_S})
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        self.assertEqual(stats['reviewKeys'], 1)
        self.assertEqual(stats['winnerKeys'], 0)
        self.assertEqual(stage_names(stage), set())

    def test_equal_valid_time_different_rules_goes_review(self):
        old = make_source('A-old', 'http://a.com/', rules=_chain('$.a'),
                          extra={'lastUpdateTime': PAST_S})
        new = make_source('A-new', 'http://a.com/', rules=_chain('$.b'),
                          extra={'lastUpdateTime': PAST_S})
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        self.assertEqual(stats['reviewKeys'], 1)
        rev = self.key_status(report, 'review')
        self.assertEqual(list(rev.values())[0]['reason'],
                         'version-conflict-equal-valid-time')
        self.assertEqual(stage_names(stage), set())

    def test_future_time_never_wins(self):
        # incoming has newer-but-future time -> treated as no valid time ->
        # conflict must go to review, not "new wins by date"
        old = make_source('A-old', 'http://a.com/', rules=_chain('$.a'),
                          extra={'lastUpdateTime': PAST_S})
        new = make_source('A-new', 'http://a.com/', rules=_chain('$.b'),
                          extra={'lastUpdateTime': FUTURE_MS})
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        self.assertEqual(stats['winnerKeys'], 0)
        self.assertEqual(stats['reviewKeys'], 1)
        self.assertEqual(stage_names(stage), set())

    def test_identical_substance_dedupes(self):
        old = make_source('A-old', 'http://a.com/', rules=_chain('$.same'),
                          extra={'weight': 5, 'lastUpdateTime': PAST_S})
        new = make_source('A-new', 'http://a.com/', rules=_chain('$.same'),
                          extra={'weight': 9, 'lastUpdateTime': PAST2_MS})
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        self.assertEqual(stats['winnerKeys'], 1)
        self.assertEqual(stats['identicalSubstanceKeys'], 1)
        self.assertEqual(stats['timeDecidedKeys'], 0)
        # deterministic: the copy with the newer valid time wins the tiebreak
        nodes = sid_nodes(stage)
        self.assertEqual(nodes['i0']['bookSourceName'], 'A-new')
        w = list(self.key_status(report, 'winner').values())[0]['winner']
        self.assertTrue(w['timeFlag'] is None)

    def test_quality_level_beats_time(self):
        # same key, both mainEligible (type 1), different substance levels:
        # old copy level 3 (toc3+content), new copy level 2 (toc2+content)
        # with a NEWER valid time -> static quality wins over time.
        thin_rules = _chain('$.t', toc={'chapterName': ''})
        old = make_source('A-old', 'http://a.com/', stype=1,
                          rules=_chain('$.a'),
                          extra={'lastUpdateTime': PAST_S})
        new = make_source('A-new', 'http://a.com/', stype=1,
                          rules=thin_rules,
                          extra={'lastUpdateTime': PAST2_MS})
        base, src, stage, report, stats = self.run_reconcile([old], [new])
        self.assertEqual(stats['winnerKeys'], 1)
        self.assertEqual(stats['qualityLevelKeys'], 1)
        self.assertEqual(stats['timeDecidedKeys'], 0)
        nodes = sid_nodes(stage)
        self.assertEqual(nodes['i0']['bookSourceName'], 'A-old')

    def test_single_untimed_candidate_kept_marked_unknown(self):
        old = make_source('A', 'http://a.com/', rules=_chain('$.a'))
        base, src, stage, report, stats = self.run_reconcile([old], [])
        self.assertEqual(stats['winnerKeys'], 1)
        self.assertEqual(stats['winnerTimeUnknown'], 1)
        w = list(self.key_status(report, 'winner').values())[0]['winner']
        self.assertEqual(w['freshness'], 'unknown')
        self.assertEqual(stage_names(stage), {'A'})

    def test_special_type_incomplete_search_kept(self):
        # type-1 source with complete toc + content but no search chain:
        # mainEligible for t!=0 -> stays in main (special-source exception)
        rules = _chain('$.t')
        rules.pop('ruleSearch')
        sp = make_source('SP', 'http://sp.com/', stype=1, rules=rules)
        base, src, stage, report, stats = self.run_reconcile([sp], [])
        self.assertEqual(stats['winnerKeys'], 1)
        self.assertEqual(stage_names(stage), {'SP'})

    def test_substantive_not_main_eligible_to_review(self):
        thin = {'ruleBookInfo': {'name': '$.n'},
                'ruleToc': {'chapterList': '$.c'}}
        s = make_source('THIN', 'http://thin.com/', rules=thin)
        base, src, stage, report, stats = self.run_reconcile([s], [])
        self.assertEqual(stats['reviewKeys'], 1)
        rev = self.key_status(report, 'review')
        self.assertEqual(list(rev.values())[0]['reason'],
                         'substantive-not-main-eligible')
        self.assertEqual(stage_names(stage), set())

    def test_disabled_quarantined(self):
        s = make_source('OFF', 'http://off.com/', extra={'enabled': False})
        base, src, stage, report, stats = self.run_reconcile([s], [])
        self.assertEqual(stats['quarantineKeys'], 1)
        self.assertEqual(stage_names(stage), set())
        q = self.key_status(report, 'quarantine')
        self.assertEqual(list(q.values())[0]['candidates'][0]['quarantine'],
                         'disabled')

    def test_nsfw_quarantined(self):
        s = make_source('禁漫天堂', 'http://jm.example/')
        base, src, stage, report, stats = self.run_reconcile([s], [])
        self.assertEqual(stats['quarantineKeys'], 1)
        q = self.key_status(report, 'quarantine')
        self.assertTrue(list(q.values())[0]['candidates'][0]['quarantine']
                        .startswith('explicit-adult'))

    def test_adult_conflict_same_key_review(self):
        clean = make_source('普通书源', 'http://a.com/', rules=_chain('$.a'))
        dirty = make_source('普通书源 🔞', 'http://a.com/', rules=_chain('$.b'),
                            extra={'lastUpdateTime': PAST_S})
        base, src, stage, report, stats = self.run_reconcile(
            [clean], [dirty])
        self.assertEqual(stats['reviewKeys'], 1)
        self.assertEqual(stats['winnerKeys'], 0)
        self.assertEqual(stage_names(stage), set())
        rev = self.key_status(report, 'review')
        self.assertEqual(list(rev.values())[0]['reason'],
                         'adult-marker-conflict')

    def test_empty_rules_quarantined(self):
        s = make_source('EMPTY', 'http://e.com/', rules={})
        base, src, stage, report, stats = self.run_reconcile([s], [])
        self.assertEqual(stats['quarantineKeys'], 1)

    def test_identity_fragment_and_type_distinct(self):
        a = make_source('A', 'http://a.com/#x')
        b = make_source('B', 'http://a.com/#y')
        c = make_source('C', 'http://a.com/#x', stype=1)
        base, src, stage, report, stats = self.run_reconcile([a, b, c], [])
        self.assertEqual(stats['identityKeys'], 3)
        self.assertEqual(stats['winnerKeys'], 3)
        self.assertEqual(stage_names(stage), {'A', 'B', 'C'})

    def test_candidate_conservation(self):
        # mix: 1 winner key (2 cands), 1 review key (2 cands),
        # 1 quarantine key (1 cand) -> all 5 accounted for
        w_old = make_source('W-old', 'http://w.com/', rules=_chain('$.a'),
                            extra={'lastUpdateTime': PAST_S})
        w_new = make_source('W-new', 'http://w.com/', rules=_chain('$.b'),
                            extra={'lastUpdateTime': PAST2_MS})
        r_old = make_source('R-old', 'http://r.com/', rules=_chain('$.a'))
        r_new = make_source('R-new', 'http://r.com/', rules=_chain('$.b'))
        q = make_source('禁漫', 'http://q.com/')
        base, src, stage, report, stats = self.run_reconcile(
            [w_old, r_old, q], [w_new, r_new])
        rep = self.read_report(report)
        total_rows = sum(len(v['candidates']) for v in rep['keys'].values())
        self.assertEqual(stats['totalCandidates'], 5)
        self.assertEqual(total_rows, 5)
        accounted = (stats['selectedSources'] + stats['loserCandidates']
                     + stats['reviewCandidates']
                     + stats['quarantinedCandidates'])
        self.assertEqual(accounted, 5)
        self.assertEqual(stats['overlapKeys'], 2)

    def test_determinism_identical_stage(self):
        base_src = [make_source('A', 'http://a.com/', rules=_chain('$.a'),
                                extra={'lastUpdateTime': PAST_S}),
                    make_source('DUP1', 'http://d.com/', rules=_chain('$.x')),
                    make_source('DUP2', 'http://d.com/', rules=_chain('$.x'))]
        incoming = [make_source('A2', 'http://a.com/', rules=_chain('$.b'),
                                extra={'lastUpdateTime': PAST2_MS}),
                    make_source('B', 'http://b.com/')]
        b1, s1, st1, r1, stats1 = self.run_reconcile(
            base_src, incoming, out_name='st1')
        # second run in a fresh tmp needs a fresh base dir
        base_src2 = os.path.join(self.tmp, 'src2.json')
        with open(base_src2, 'w', encoding='utf-8') as f:
            json.dump(base_src, f, ensure_ascii=False)
        corpus2 = os.path.join(self.tmp, 'base2')
        os.makedirs(os.path.join(corpus2, 'shards'))
        argv_bak = sys.argv
        try:
            sys.argv = ['build_corpus.py', '--src', base_src2,
                        '--out', corpus2]
            bc.main()
        finally:
            sys.argv = argv_bak
        idx2 = read_index(corpus2)
        idx2['provenance'] = [{'firstSourceIndex': len(base_src)}]
        with open(os.path.join(corpus2, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx2, f)
        st2 = os.path.join(self.tmp, 'st2')
        stats2 = rc.reconcile_corpus(corpus2, s1, st2, None,
                                     merge_source='test-snap',
                                     now_ms=NOW_MS)
        for k in ('identityKeys', 'winnerKeys', 'reviewKeys',
                  'quarantineKeys', 'selectedSources'):
            self.assertEqual(stats1[k], stats2[k])
        i1 = read_index(st1)
        i2 = read_index(st2)
        self.assertEqual(i1['rec'], i2['rec'])
        self.assertEqual(i1['fam'], i2['fam'])

    def test_stage_rejects_existing_nonempty_dir(self):
        base = build_base([make_source('A', 'http://a.com/')], self.tmp,
                          prov=True)
        src = os.path.join(self.tmp, 'inc.json')
        with open(src, 'w') as f:
            json.dump([], f)
        stage = os.path.join(self.tmp, 'busy')
        os.makedirs(stage)
        with open(os.path.join(stage, 'x'), 'w') as f:
            f.write('keep me')
        with self.assertRaises(ValueError):
            rc.reconcile_corpus(base, src, stage, None, now_ms=NOW_MS)
        # user file untouched
        with open(os.path.join(stage, 'x')) as f:
            self.assertEqual(f.read(), 'keep me')

    def test_bad_input_never_touches_base(self):
        base = build_base([make_source('A', 'http://a.com/')], self.tmp,
                          prov=True)
        with open(os.path.join(base, 'index.json'), 'rb') as f:
            before = f.read()
        src = os.path.join(self.tmp, 'bad.json')
        with open(src, 'w') as f:
            f.write('{broken')
        with self.assertRaises(Exception):
            rc.reconcile_corpus(base, src,
                                os.path.join(self.tmp, 'st'), None,
                                now_ms=NOW_MS)
        with open(os.path.join(base, 'index.json'), 'rb') as f:
            self.assertEqual(f.read(), before)
        self.assertFalse(os.path.exists(os.path.join(self.tmp, 'st')))

    def test_no_double_counting_of_appended_tail(self):
        # base already contains an appended tail; provenance marks
        # firstSourceIndex.  Candidates must only count the head.
        head = make_source('A', 'http://a.com/', rules=_chain('$.a'),
                           extra={'lastUpdateTime': PAST_S})
        tail = make_source('T', 'http://t.com/')
        base_dir = build_base([head, tail], self.tmp)
        idx = read_index(base_dir)
        idx['provenance'] = [{'firstSourceIndex': 1}]
        with open(os.path.join(base_dir, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx, f)
        src = os.path.join(self.tmp, 'inc.json')
        with open(src, 'w', encoding='utf-8') as f:
            json.dump([make_source('T', 'http://t.com/')], f)
        stats = rc.reconcile_corpus(
            base_dir, src, os.path.join(self.tmp, 'st'), None,
            now_ms=NOW_MS)
        # 1 head candidate + 1 incoming = 2 total, not 3
        self.assertEqual(stats['totalCandidates'], 2)
        self.assertEqual(stats['baseSources'], 1)

    def test_provenance_and_stage_contract(self):
        old = make_source('A', 'http://a.com/', rules=_chain('$.a'),
                          extra={'lastUpdateTime': PAST_S})
        base, src, stage, report, stats = self.run_reconcile(
            [old], [make_source('B', 'http://b.com/')])
        idx = read_index(stage)
        prov = idx['provenance']
        self.assertEqual(len(prov), 1)
        p = prov[-1]
        self.assertEqual(p['mergePolicy'], 'global-dedupe-rebuild')
        self.assertEqual(p['baseSources'], 1)
        self.assertEqual(p['incomingSources'], 1)
        self.assertEqual(p['totalCandidates'], 2)
        self.assertEqual(p['identityKeys'], 2)
        self.assertEqual(p['selectedSources'], 2)
        self.assertIn('reviewKeys', p)
        self.assertIn('quarantinedKeys', p)
        self.assertEqual(p['selectedOldSources'], 1)
        self.assertEqual(p['selectedIncomingSources'], 1)
        self.assertIsInstance(p['selectedNewSourceIndices'], list)
        self.assertEqual(len(p['selectedNewSourceIndices']), 1)
        # the index must point at the 'B' winner record, not input order
        new_i = p['selectedNewSourceIndices'][0]
        nodes = sid_nodes(stage)
        self.assertEqual(nodes['i%d' % new_i]['bookSourceName'], 'B')
        self.assertEqual(len(p['baseIndexSha256']), 64)
        self.assertEqual(len(p['sourceSha256']), 64)
        self.assertEqual(len(p['auditSha256']), 64)
        # stage validates end-to-end
        check = ac.load_and_validate_base(stage)
        self.assertEqual(check['index']['n'], 2)
        # winner records equal whitelist-cleaned source
        for r in idx['rec']:
            node = nodes['i%d' % r['i']]
            self.assertEqual(set(node.keys()) - set(bc.ALLOWED_FIELDS),
                             set())


class PathSafetyTest(ReconcileBase):
    def _setup(self):
        base = build_base([make_source('A', 'http://a.com/')], self.tmp,
                          prov=True)
        src = os.path.join(self.tmp, 'src.json')
        with open(src, 'w', encoding='utf-8') as f:
            json.dump([make_source('B', 'http://b.com/')], f)
        with open(src, 'rb') as f:
            src_bytes = f.read()
        return base, src, src_bytes

    def test_report_equal_src_refused(self):
        base, src, src_bytes = self._setup()
        with self.assertRaises(ValueError):
            rc.reconcile_corpus(base, src, os.path.join(self.tmp, 'st'),
                                src, now_ms=NOW_MS)
        with open(src, 'rb') as f:
            self.assertEqual(f.read(), src_bytes)

    def test_report_inside_src_dir_refused(self):
        base, src, src_bytes = self._setup()
        srcdir = os.path.join(self.tmp, 'srcdir')
        os.makedirs(srcdir)
        src2 = os.path.join(srcdir, 'merged.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([make_source('B', 'http://b.com/')], f)
        # report inside the src file's parent dir? report IS allowed in a
        # sibling dir; what is refused is a path inside the src *file* or
        # equal to it.  A report under the src dir is fine only when src is
        # a file, but report == src parent containment is about files: make
        # the case concrete -- report path under a *directory* src is the
        # dangerous shape; here src is a file so only equality applies.
        # Stronger check: report == a path that is an ancestor of src.
        with self.assertRaises(ValueError):
            rc.reconcile_corpus(
                base, src2, os.path.join(self.tmp, 'st2'),
                os.path.join(self.tmp, 'srcdir'), now_ms=NOW_MS)
        # src content unchanged
        with open(src2, 'rb') as f:
            self.assertEqual(
                f.read(),
                json.dumps([make_source('B', 'http://b.com/')],
                           ensure_ascii=False).encode('utf-8'))

    def test_stage_inside_src_file_or_dir_refused(self):
        base, src, src_bytes = self._setup()
        # stage == src file
        with self.assertRaises(ValueError):
            rc.reconcile_corpus(base, src, src, None, now_ms=NOW_MS)
        # src inside a dir used as stage
        srcdir = os.path.join(self.tmp, 'sdir')
        os.makedirs(srcdir)
        src2 = os.path.join(srcdir, 'merged.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([make_source('B', 'http://b.com/')], f)
        with self.assertRaises(ValueError):
            rc.reconcile_corpus(base, src2, srcdir, None, now_ms=NOW_MS)
        # src file bytes still intact
        with open(src2, 'rb') as f:
            self.assertIn(b'http://b.com/', f.read())
        with open(src, 'rb') as f:
            self.assertEqual(f.read(), src_bytes)


class AdultHostTest(unittest.TestCase):
    def test_host_with_port_and_userinfo_still_flagged(self):
        ev = rc.evaluate_candidate
        for url in ('http://po18.example:80/',
                    'http://user:pw@po18.example:8080/x',
                    'https://sub.po18.example/a?b=1',
                    'http://18comic.example:443/'):
            c = ev(make_source('S', url), 'incoming', 0, NOW_MS)
            self.assertEqual(c['adult'], 'nsfw-host', url)
            self.assertEqual(c['quarantineReason'],
                             'explicit-adult:nsfw-host', url)
        # benign lookalikes must NOT be flagged
        for url in ('http://spo18.example/', 'http://po18x.example/',
                    'http://apo18.example/'):
            c = ev(make_source('S', url), 'incoming', 0, NOW_MS)
            self.assertIsNone(c['adult'], url)
        # ordinary 海棠 in a plain name is not an explicit marker
        c = ev(make_source('海棠书屋', 'http://hts.example/'),
               'incoming', 0, NOW_MS)
        self.assertIsNone(c['adult'])
        self.assertIsNone(c['quarantineReason'])


class SelectedNewIndicesTest(ReconcileBase):
    def test_indices_are_final_rec_positions(self):
        # two incoming winners land wherever host-sorting puts them; the
        # provenance list must contain their FINAL rec.i values.
        base_src = [
            make_source('A', 'http://zzz.com/', rules=_chain('$.a'),
                        extra={'lastUpdateTime': PAST_S}),
            make_source('DUP1', 'http://d.com/', rules=_chain('$.x')),
            make_source('DUP2', 'http://d.com/', rules=_chain('$.x')),
        ]
        incoming = [
            make_source('N1', 'http://aaa.com/', rules=_chain('$.n1'),
                        extra={'lastUpdateTime': PAST_S}),
            make_source('N2', 'http://yyy.com/', rules=_chain('$.n2'),
                        extra={'lastUpdateTime': PAST_S}),
        ]
        base, src, stage, report, stats = self.run_reconcile(
            base_src, incoming)
        idx = read_index(stage)
        p = idx['provenance'][-1]
        inds = p['selectedNewSourceIndices']
        self.assertEqual(sorted(inds), inds)
        self.assertEqual(len(set(inds)), len(inds))
        self.assertEqual(len(inds), p['selectedIncomingSources'])
        self.assertEqual(len(inds), stats['selectedIncomingSources'])
        nodes = sid_nodes(stage)
        got = sorted(nodes['i%d' % i]['bookSourceName'] for i in inds)
        self.assertEqual(got, ['N1', 'N2'])
        self.assertTrue(all(0 <= i < idx['n'] for i in inds))
        # base winners count too
        self.assertEqual(p['selectedOldSources'], 2)


class ReuseRebuildBaseTest(ReconcileBase):
    """Reuse the REAL output of a rebuild (stage1) as the next --base.

    This is the BUILDING.md incremental flow: after the wholesale rebuild is
    installed, the next snapshot reconciles against the rebuilt corpus.  The
    rebuilt base has no append-only tail, so every one of its records is a
    candidate and none may be discarded as a same-batch tail.
    """

    def _rebuilt_base(self):
        """Run reconcile once and return (stage1, stats1, incoming2 src)."""
        base_src = [
            make_source('OLD', 'http://a.com/', rules=_chain('$.old'),
                        extra={'lastUpdateTime': PAST_S}),
            make_source('KEEP', 'http://b.com/', rules=_chain('$.b')),
            make_source('GONE', 'http://c.com/', rules=_chain('$.c')),
        ]
        incoming1 = [
            # same key as OLD but newer rules + newer time -> updates it
            make_source('OLD2', 'http://a.com/', rules=_chain('$.new'),
                        extra={'lastUpdateTime': PAST2_MS}),
        ]
        _, _, stage1, _, stats1 = self.run_reconcile(
            base_src, incoming1, out_name='stage1')
        return stage1, stats1

    def test_rebuild_base_is_reused_as_whole_candidate_set(self):
        stage1, stats1 = self._rebuilt_base()
        idx1 = read_index(stage1)
        # genuine rebuild provenance, not a hand-faked firstSourceIndex
        self.assertEqual(idx1['provenance'][-1]['mergePolicy'],
                         'global-dedupe-rebuild')
        self.assertNotIn('firstSourceIndex', idx1['provenance'][-1])
        # the whole rebuilt index becomes the next base's candidates
        self.assertEqual(rc.detect_base_n(idx1), idx1['n'])

        src2 = os.path.join(self.tmp, 'incoming2.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([make_source('NEW', 'http://d.com/')], f)
        stage2 = os.path.join(self.tmp, 'stage2')
        stats2 = rc.reconcile_corpus(stage1, src2, stage2, None,
                                     merge_source='snap2', now_ms=NOW_MS)

        # no tail discarded: every stage1 record plus the incoming snapshot
        self.assertEqual(stats2['baseSources'], idx1['n'])
        self.assertEqual(stats2['totalCandidates'],
                         idx1['n'] + 1)
        self.assertEqual(stats2['selectedOldSources'], idx1['n'])
        # stage2 is a valid corpus with the new record added
        idx2 = read_index(stage2)
        self.assertEqual(idx2['n'], idx1['n'] + 1)
        ac.load_and_validate_base(stage2)

    def test_reused_base_same_key_update_applies(self):
        # second snapshot updates the OLD key by a newer valid time
        stage1, _ = self._rebuilt_base()
        nodes1 = sid_nodes(stage1)
        idx1 = read_index(stage1)
        # after run 1 the key http://a.com/ is the newer rules
        name_by_key = {}
        for r in idx1['rec']:
            n = nodes1['i%d' % r['i']]
            name_by_key[n['bookSourceUrl']] = n['bookSourceName']
        self.assertEqual(name_by_key['http://a.com/'], 'OLD2')

        src2 = os.path.join(self.tmp, 'inc2.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([
                make_source('OLD3', 'http://a.com/', rules=_chain('$.newest'),
                            extra={'lastUpdateTime': PAST2_MS + 60_000}),
            ], f)
        stage2 = os.path.join(self.tmp, 'stage2')
        stats2 = rc.reconcile_corpus(stage1, src2, stage2, None,
                                     merge_source='snap2', now_ms=NOW_MS)
        self.assertEqual(stats2['timeDecidedKeys'], 1)
        self.assertEqual(stats2['timeDecidedWinnerBySide']['incoming'], 1)
        nodes2 = sid_nodes(stage2)
        content = {n['bookSourceUrl']: n['ruleContent']['content']
                   for n in nodes2.values()}
        self.assertEqual(content['http://a.com/'], '$.newest')
        # the rebuilt base record is still present as a loser, not dropped
        self.assertEqual(stats2['loserCandidates'], 1)

    def test_reused_base_older_snapshot_does_not_win(self):
        # a stale second snapshot must not overwrite the rebuilt base
        stage1, _ = self._rebuilt_base()
        src2 = os.path.join(self.tmp, 'inc2.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([
                make_source('STALE', 'http://a.com/', rules=_chain('$.stale'),
                            extra={'lastUpdateTime': PAST_S}),
            ], f)
        stage2 = os.path.join(self.tmp, 'stage2')
        stats2 = rc.reconcile_corpus(stage1, src2, stage2, None,
                                     merge_source='snap2', now_ms=NOW_MS)
        self.assertEqual(stats2['timeDecidedWinnerBySide']['base'], 1)
        nodes2 = sid_nodes(stage2)
        content = {n['bookSourceUrl']: n['ruleContent']['content']
                   for n in nodes2.values()}
        self.assertEqual(content['http://a.com/'], '$.new')

    def test_rerun_on_rebuilt_base_is_stable_and_reusable(self):
        # applying the SAME incoming snapshot twice to a rebuilt base yields
        # identical rec/fam/n and the output is again a usable base.
        stage1, _ = self._rebuilt_base()
        src2 = os.path.join(self.tmp, 'inc2.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([make_source('NEW', 'http://d.com/')], f)
        st_a = os.path.join(self.tmp, 'rerun_a')
        st_b = os.path.join(self.tmp, 'rerun_b')
        sa = rc.reconcile_corpus(stage1, src2, st_a, None,
                                 merge_source='snap2', now_ms=NOW_MS)
        sb = rc.reconcile_corpus(stage1, src2, st_b, None,
                                 merge_source='snap2', now_ms=NOW_MS)
        for k in ('n', 'rec', 'fam'):
            self.assertEqual(read_index(st_a)[k], read_index(st_b)[k])
        self.assertEqual(sa['selectedSources'], sb['selectedSources'])
        # and the rerun's output is itself a rebuild base usable as --base
        self.assertEqual(rc.detect_base_n(read_index(st_a)),
                         read_index(st_a)['n'])

    def test_base_sources_on_rebuilt_base_semantics(self):
        stage1, _ = self._rebuilt_base()
        idx1 = read_index(stage1)
        src2 = os.path.join(self.tmp, 'inc2.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([make_source('NEW', 'http://d.com/')], f)
        # explicit base_sources == n is accepted (declares the whole index)
        st_ok = os.path.join(self.tmp, 'st_ok')
        stats = rc.reconcile_corpus(stage1, src2, st_ok, None,
                                    merge_source='snap2', now_ms=NOW_MS,
                                    base_sources=idx1['n'])
        self.assertEqual(stats['baseSources'], idx1['n'])
        # a contradictory boundary is refused, whatever the resolved value
        for bad in (0, idx1['n'] - 1, idx1['n'] + 5):
            with self.assertRaises(ValueError):
                rc.reconcile_corpus(
                    stage1, src2, os.path.join(self.tmp, 'st_bad_%d' % bad),
                    None, now_ms=NOW_MS, base_sources=bad)

    def test_stale_legacy_boundary_beyond_n_is_ignored(self):
        # Regression for the reported crash: a rebuilt corpus whose history
        # still holds the pre-rebuild append-only firstSourceIndex (26861 > n)
        # must reconcile by reusing its real records, not raise out-of-range.
        stage1, _ = self._rebuilt_base()
        idx1 = read_index(stage1)
        idx1['provenance'] = [
            {'mergePolicy': 'keep-base-append-only',
             'firstSourceIndex': 26861},
            {'mergePolicy': 'global-dedupe-rebuild', 'baseSources': 3},
        ]
        with open(os.path.join(stage1, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(idx1, f, ensure_ascii=False, separators=(',', ':'))
        src2 = os.path.join(self.tmp, 'inc2.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([make_source('NEW', 'http://d.com/')], f)
        stage2 = os.path.join(self.tmp, 'stage2')
        stats = rc.reconcile_corpus(stage1, src2, stage2, None,
                                    merge_source='snap2', now_ms=NOW_MS)
        self.assertEqual(stats['baseSources'], idx1['n'])


class InstalledCorpusReconcileTest(unittest.TestCase):
    """The shipped corpus is a rebuild output and must be directly reusable."""

    CORPUS = os.path.join(os.path.dirname(SCRIPT_DIR),
                          'app', 'src', 'main', 'assets', 'corpus')

    def test_installed_corpus_detects_as_rebuild_base(self):
        idx_path = os.path.join(self.CORPUS, 'index.json')
        if not os.path.isfile(idx_path):
            self.skipTest('installed corpus assets not present')
        with open(idx_path, encoding='utf-8') as f:
            idx = json.load(f)
        prov = idx.get('provenance') or []
        if not any(isinstance(e, dict) and e.get('mergePolicy')
                   == 'global-dedupe-rebuild' for e in prov):
            self.skipTest('installed corpus is not a rebuild output')
        # must not raise and must treat the whole index as candidates
        self.assertEqual(rc.detect_base_n(idx), idx['n'])


class ReconcileCliSmokeTest(ReconcileBase):
    """The documented CLI must run end-to-end on a reused rebuilt base."""

    def test_cli_reuses_rebuilt_base(self):
        stage1, _ = ReuseRebuildBaseTest._rebuilt_base(self)
        src2 = os.path.join(self.tmp, 'inc2.json')
        with open(src2, 'w', encoding='utf-8') as f:
            json.dump([make_source('NEW', 'http://d.com/')], f)
        stage2 = os.path.join(self.tmp, 'cli_stage2')
        env = dict(os.environ)
        env['PYTHONDONTWRITEBYTECODE'] = '1'
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, 'reconcile_corpus.py'),
             '--base', stage1, '--src', src2, '--stage', stage2,
             '--merge-source', 'snap2'],
            capture_output=True, text=True, encoding='utf-8', env=env)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(read_index(stage2)['n'],
                         read_index(stage1)['n'] + 1)
        ac.load_and_validate_base(stage2)


if __name__ == '__main__':
    unittest.main()
