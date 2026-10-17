#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Global identity-key reconciliation for the yuedu-MCP book-source corpus.

Unlike append_corpus.py (keep-base, append-only), this tool re-evaluates the
WHOLE corpus: every record already in the base corpus plus every incoming
bookSource record is bucketed by the same conservative identity key used by
append_corpus.identity_key / sourcehub identityKey (type + normalized URL:
only http/https scheme+host case, default port, empty root path and empty
fragment are folded; www/userinfo/path case/query/non-empty fragment and
non-http(s) ids stay verbatim).

Per bucket:

- Each candidate is statically evaluated with the sourcehub quality rules:
  name/url present, bookSourceType in 0..4, rule fields must be objects,
  substantive reading chain (type 0 needs ruleToc.chapterList/chapterName/
  chapterUrl + ruleContent.content; type 1..4 need the three toc fields or
  >=2 toc fields plus content), non-empty rules, enabled != false, and no
  explicit adult marker (sourcehub explicitAdultMarker semantics).
- Candidates failing a hard rule are quarantined (recorded, not shipped).
- If any same-key copy carries an explicit adult marker while another copy
  is clean, the whole key goes to review (possible masquerade) instead of
  silently shipping the clean-looking copy.
- Eligible candidates that are not mainEligible (substantive rules but no
  complete reading chain) do not enter the main export; the key goes to
  review/offline reference instead of pretending to be readable.
- Eligible candidates whose rules+dependency fields normalize to the same
  substance fingerprint are treated as one version: deterministic pick,
  time recorded as known/unknown, never claimed "newest".
- Different substance fingerprints inside one key are a version conflict:
  * static quality level wins over time (a readable new copy beats an
    unreadable old one);
  * at the top level, only a UNIQUE max valid lastUpdateTime wins;
  * missing/invalid time on any competing fingerprint, or equal valid
    times for different fingerprints, or same time different rules ->
    the whole key is moved to review (no "newest" is claimed).
- A single uncontested eligible candidate is kept even without a valid
  time; it is flagged time-unknown, never called "latest".

Valid lastUpdateTime: numeric seconds (>=1e9) or ms (>=1e12), >0, <= now.
Non-numeric/zero/future/sentinel values are invalid and never used; no
listing date, file mtime or index gen is ever substituted for rule time.

Output: a BRAND NEW corpus stage directory produced by the unmodified
build_corpus.py on the winners list only (no old shards copied), plus an
offline audit report.json accounting for every candidate. The index gains
a provenance entry with mergePolicy 'global-dedupe-rebuild'.

Base lineage: a base whose latest provenance is 'global-dedupe-rebuild' is
itself a rebuild output, so all of its index.n records are candidates and
there is no append-only tail to protect (base_n == n); a stale pre-rebuild
firstSourceIndex from replaced history is ignored rather than mistaken for a
same-batch tail.  A base still on the append-only strategy keeps the
original tail dedupe protection (only records before firstSourceIndex are
candidates).  --base-sources may declare the count explicitly but must agree
with the resolved boundary.

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
import time
from datetime import date, datetime, timezone
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import append_corpus  # noqa: E402
import build_corpus  # noqa: E402

RULE_OBJECT_FIELDS = [
    'ruleSearch', 'ruleBookInfo', 'ruleToc', 'ruleContent',
    'ruleExplore', 'ruleReview',
]

# Scalar fields that affect fetch/parse behaviour: two records that differ in
# any of these are different versions even if the rule objects match.
DEPENDENCY_FIELDS = [
    'searchUrl', 'exploreUrl', 'bookUrlPattern', 'header', 'jsLib',
    'loginUrl', 'loginCheckJs', 'loginUi', 'coverDecodeJs',
    'enabledCookieJar', 'enabledExplore', 'eventListener', 'concurrentRate',
    'customButton', 'variableComment',
]

# Explicit adult markers — same semantics as sourcehub
# scripts/source-quality.mjs explicitAdultMarker().  Latin tokens use ASCII
# word boundaries (JS \b is ASCII-only; Python \b treats CJK as word chars,
# which would silently change matches like 'po18小说').
_ASCII_B = r'(?<![A-Za-z0-9_])(?:%s)(?![A-Za-z0-9_])'
NSFW_TAG_RE = re.compile(
    r'🔞|18\s*禁|18\+|' + _ASCII_B % 'nsfw' + '|' + _ASCII_B % 'r-?18' + '|'
    + _ASCII_B % 'xxx' + r'|成人(向|小说|内容|漫画|视频|文学|站)',
    re.IGNORECASE)
NSFW_NAME_RE = re.compile(
    r'禁漫|第一版主|nhentai|exhentai|e-hentai|绅士漫画|里番|18comic|jmcomic|'
    + _ASCII_B % 'po18',
    re.IGNORECASE)
NSFW_HOST_RE = re.compile(
    r'(^|\.)(po18|haitang[a-z0-9]*|18comic|jmcomic|nhentai|exhentai|'
    r'e-hentai|wnacg|banzhu|cool18)\.',
    re.IGNORECASE)


def _canon(path):
    """Case/symlink-insensitive canonical path for safe comparisons."""
    return os.path.normcase(os.path.realpath(path))


def _is_within(path, parent):
    p, q = _canon(path), _canon(parent)
    if p == q:
        return True
    try:
        return os.path.commonpath([p, q]) == q
    except ValueError:
        return False


def _hostname_only(raw_url, fallback):
    """Hostname only (no userinfo/port) for adult-host matching.

    safe_extract_host returns netloc including userinfo/port, which would
    hide 'po18.example:80' from NSFW_HOST_RE.  Parse the hostname out; on any
    parse error or when no hostname exists fall back to the raw host string.
    """
    url = str(raw_url or '').strip()
    if not url:
        return fallback
    m = re.search(r'https?://', url)
    if m and m.start() > 0:
        url = url[m.start():]
    try:
        if url.startswith(('http://', 'https://')):
            host = urlparse(url).hostname
        else:
            host = urlparse('http://' + url).hostname
    except ValueError:
        return fallback
    return (host or fallback or 'invalid').lower()


# ============ field normalization ============

def norm_type(v):
    """Return (type_int, flag|None).  Absent-ish values normalize to 0 with a
    flag; genuinely invalid values return (None, flag)."""
    if v is None:
        return 0, 'missing-type'
    if isinstance(v, bool):
        return None, 'bool-type'
    if isinstance(v, int):
        if 0 <= v <= 4:
            return v, None
        return None, 'type-out-of-range:%r' % v
    if isinstance(v, str):
        s = v.strip()
        if s == '':
            return 0, 'empty-type'
        if s.isdigit() and 0 <= int(s) <= 4:
            return int(s), 'string-type'
        return None, 'bad-type:%r' % v[:40]
    return None, 'bad-type-kind:%s' % type(v).__name__


def _non_empty_str(v):
    return isinstance(v, str) and len(v.strip()) > 0


def _has_leaf(v):
    if v is None:
        return False
    if isinstance(v, str):
        return len(v.strip()) > 0
    if isinstance(v, bool):
        return True
    if isinstance(v, (int, float)):
        return True
    if isinstance(v, list):
        return any(_has_leaf(x) for x in v)
    if isinstance(v, dict):
        return any(_has_leaf(x) for x in v.values())
    return False


def _rule_obj_substance(v):
    if v is None:
        return {'present': False, 'malformed': False, 'nonEmpty': 0}
    if not isinstance(v, dict):
        return {'present': True, 'malformed': True, 'nonEmpty': 0}
    n = 0
    for sv in v.values():
        if _has_leaf(sv):
            n += 1
    return {'present': True, 'malformed': False, 'nonEmpty': n}


def rule_substance(cleaned, source_type):
    """Port of sourcehub ruleSubstance()."""
    s = {}
    malformed = False
    for f in RULE_OBJECT_FIELDS:
        s[f] = _rule_obj_substance(cleaned.get(f))
        if s[f]['malformed']:
            malformed = True
    rtoc = cleaned.get('ruleToc')
    rtoc = rtoc if isinstance(rtoc, dict) else {}
    rc = cleaned.get('ruleContent')
    rc = rc if isinstance(rc, dict) else {}
    toc = {
        'chapterList': _non_empty_str(rtoc.get('chapterList')),
        'chapterName': _non_empty_str(rtoc.get('chapterName')),
        'chapterUrl': _non_empty_str(rtoc.get('chapterUrl')),
    }
    toc_count = sum(1 for v in toc.values() if v)
    has_content = _non_empty_str(rc.get('content'))
    has_search = _non_empty_str(cleaned.get('searchUrl')) \
        or s['ruleSearch']['nonEmpty'] > 0
    has_explore = _non_empty_str(cleaned.get('exploreUrl')) \
        or s['ruleExplore']['nonEmpty'] > 0
    any_substantive = (
        toc_count > 0 or has_content or has_search or has_explore
        or s['ruleBookInfo']['nonEmpty'] > 0
        or s['ruleReview']['nonEmpty'] > 0)

    if toc_count == 3 and has_content:
        level = 3
    elif (toc_count >= 2 and (has_content or has_search or has_explore)) \
            or toc_count == 3:
        level = 2
    elif any_substantive:
        level = 1
    else:
        level = 0

    t = int(source_type) if source_type else 0
    if t == 0:
        main_eligible = toc_count == 3 and has_content
    else:
        main_eligible = toc_count == 3 or (toc_count >= 2 and has_content)

    return {
        'level': level,
        'tocCount': toc_count,
        'toc': toc,
        'hasContent': has_content,
        'hasSearch': has_search,
        'hasExplore': has_explore,
        'anySubstantive': any_substantive,
        'malformed': malformed,
        'mainEligible': main_eligible,
        'nonEmptyRuleFields': [f for f in RULE_OBJECT_FIELDS
                               if s[f]['nonEmpty'] > 0],
    }


def parse_rule_time(v, now_ms):
    """Port of sourcehub parseRuleTime().  Returns (ms|None, flag|None)."""
    if v is None:
        return None, None
    s = str(v).strip()
    if not re.match(r'^-?\d+$', s):
        return None, 'non-numeric:%s' % s[:40]
    n = int(s)
    if n <= 0:
        return None, 'non-positive:%s' % s[:20]
    now = now_ms if now_ms else int(time.time() * 1000)
    if n >= 1e12:
        ms = n
    elif n >= 1e9:
        ms = n * 1000
    else:
        return None, 'implausible:%s' % s[:20]
    if ms > now:
        return None, 'future:%s' % s[:20]
    return ms, None


def explicit_adult_marker(name, group, host):
    """Port of sourcehub explicitAdultMarker() on (name, group, host)."""
    ng = '%s %s' % (name or '', group or '')
    if NSFW_TAG_RE.search(ng):
        return 'nsfw-tag'
    if NSFW_NAME_RE.search(ng):
        return 'nsfw-name'
    if host and host != 'invalid' and NSFW_HOST_RE.search(host + '.'):
        return 'nsfw-host'
    return None


def substance_fingerprint(cleaned):
    """Deterministic digest of rules + dependency fields after normalization.

    Rule objects contribute every non-empty leaf as 'field.path=trimmed';
    dependency scalar fields contribute 'field=trimmed'.  Metadata such as
    name/group/weight/enabled/lastUpdateTime is intentionally excluded.
    """
    parts = []

    def walk(prefix, v):
        if isinstance(v, dict):
            for k in sorted(v.keys()):
                walk('%s.%s' % (prefix, k), v[k])
        elif isinstance(v, list):
            for j, x in enumerate(v):
                walk('%s[%d]' % (prefix, j), x)
        elif isinstance(v, str):
            t = v.strip()
            if t:
                parts.append('%s=%s' % (prefix, t))
        elif isinstance(v, bool):
            parts.append('%s=%s' % (prefix, 'true' if v else 'false'))
        elif isinstance(v, (int, float)):
            parts.append('%s=%r' % (prefix, v))

    for f in RULE_OBJECT_FIELDS:
        v = cleaned.get(f)
        if isinstance(v, dict):
            walk(f, v)
        elif isinstance(v, str) and v.strip():
            parts.append('%s=%s' % (f, v.strip()))
    for f in DEPENDENCY_FIELDS:
        v = cleaned.get(f)
        if isinstance(v, str) and v.strip():
            parts.append('%s=%s' % (f, v.strip()))
        elif isinstance(v, bool):
            parts.append('%s=%s' % (f, 'true' if v else 'false'))
        elif isinstance(v, (int, float)):
            parts.append('%s=%r' % (f, v))
        elif isinstance(v, (dict, list)):
            walk(f, v)
    return hashlib.sha1('\n'.join(parts).encode('utf-8')).hexdigest()


# ============ candidate evaluation ============

def evaluate_candidate(record, side, index, now_ms):
    """Static quality evaluation of one candidate source record."""
    url = record.get('bookSourceUrl')
    url = url.strip() if isinstance(url, str) else None
    if url == '':
        url = None
    name = record.get('bookSourceName')
    name = name.strip() if isinstance(name, str) else None
    if name == '':
        name = None
    stype, type_flag = norm_type(record.get('bookSourceType'))
    sub = rule_substance(record, stype if stype is not None else 0)
    host = build_corpus.safe_extract_host(url or
                                          record.get('bookSourceUrl'))
    # adult-host matching must see the pure hostname, not userinfo:port
    adult_host = _hostname_only(url or record.get('bookSourceUrl'), host)
    adult = explicit_adult_marker(name or '', record.get('bookSourceGroup'),
                                  adult_host)
    time_ms, time_flag = parse_rule_time(record.get('lastUpdateTime'), now_ms)

    reason = None
    if url is None:
        reason = 'missing-url'
    elif name is None:
        reason = 'missing-name'
    elif stype is None or type_flag in ('bool-type',) \
            or (type_flag or '').startswith(('type-out-of-range', 'bad-type')):
        reason = 'malformed-field-type'
    elif sub['malformed']:
        reason = 'malformed-rule-type'
    elif not sub['anySubstantive']:
        reason = 'empty-rules'
    elif record.get('enabled') is False:
        reason = 'disabled'
    elif adult:
        reason = 'explicit-adult:' + adult

    return {
        'side': side,
        'index': index,
        'record': record,
        'url': url,
        'name': name,
        'type': stype,
        'typeFlag': type_flag,
        'key': append_corpus.identity_key(
            record.get('bookSourceUrl') if url is not None else '',
            stype if stype is not None else 0),
        'host': host,
        'adult': adult,
        'timeMs': time_ms,
        'timeFlag': time_flag,
        'enabled': record.get('enabled') is not False,
        'substance': sub,
        'fingerprint': substance_fingerprint(record),
        'quarantineReason': reason,
    }


def cand_brief(c):
    return {
        'side': c['side'],
        'index': c['index'],
        'name': c['name'],
        'type': c['type'],
        'level': c['substance']['level'],
        'mainEligible': c['substance']['mainEligible'],
        'timeMs': c['timeMs'],
        'timeFlag': c['timeFlag'],
        'typeFlag': c['typeFlag'],
        'fp': c['fingerprint'][:12],
        'quarantine': c['quarantineReason'],
        'adult': c['adult'],
    }


def _ordinal(c):
    return (0 if c['side'] == 'base' else 1, c['index'])


def _pick_tiebreak(cands):
    """Deterministic winner among same-fingerprint candidates: completeness
    first, then valid time, then stable origin order."""
    def rank(c):
        return (c['substance']['level'], c['timeMs'] or 0)
    best = None
    for c in sorted(cands, key=_ordinal):
        if best is None or rank(c) > rank(best):
            best = c
    return best


def decide_bucket(members):
    """Return (status, winner|None, reason, notes) for one identity bucket.

    status in {'winner', 'review', 'quarantine'}.
    """
    eligible = [m for m in members if m['quarantineReason'] is None]
    adult_marked = [m for m in members if m['adult']]

    if not eligible:
        return 'quarantine', None, 'all-candidates-quarantined', {}

    if adult_marked:
        # A clean copy sharing a key with an explicit-adult copy may be a
        # masquerade; never ship it blindly.
        return 'review', None, 'adult-marker-conflict', {}

    mains = [m for m in eligible if m['substance']['mainEligible']]
    if not mains:
        return 'review', None, 'substantive-not-main-eligible', {}

    fps = {}
    for m in mains:
        fps.setdefault(m['fingerprint'], []).append(m)

    if len(fps) == 1:
        w = _pick_tiebreak(mains)
        return 'winner', w, 'identical-substance', {
            'timeKnown': w['timeMs'] is not None}

    # Multiple substance versions: static quality level first.
    top_level = max(m['substance']['level'] for m in mains)
    top = [m for m in mains if m['substance']['level'] == top_level]
    top_fps = {m['fingerprint'] for m in top}
    if len(top_fps) == 1:
        w = _pick_tiebreak(top)
        return 'winner', w, 'quality-level-priority', {
            'timeKnown': w['timeMs'] is not None}

    # Competing versions at the same top level need valid distinct times.
    untimed = [m for m in top if m['timeMs'] is None]
    if untimed:
        return 'review', None, 'version-conflict-no-valid-time', {}
    max_t = max(m['timeMs'] for m in top)
    newest = [m for m in top if m['timeMs'] == max_t]
    newest_fps = {m['fingerprint'] for m in newest}
    if len(newest_fps) != 1:
        return 'review', None, 'version-conflict-equal-valid-time', {}
    w = _pick_tiebreak(newest)
    return 'winner', w, 'valid-time-newer', {'timeKnown': True}


# ============ main flow ============

def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def detect_base_n(index, override=None):
    """Resolve how many leading base records are reconciliation candidates.

    A base corpus belongs to one of two lineages, told apart by the
    chronologically appended provenance:

    * ``mergePolicy == 'global-dedupe-rebuild'`` — the corpus was rebuilt
      wholesale by this tool, so every one of its ``index.n`` records is a
      legitimate candidate and there is no append-only tail.  ``base_n == n``.
      A stale ``firstSourceIndex`` left over from the pre-rebuild append-only
      history is NOT the active boundary and must not be mistaken for a
      same-batch snapshot tail (it may even exceed the rebuilt ``n``).
    * an append-only ``firstSourceIndex`` boundary — records beyond it are
      that run's appended tail (the previous incoming snapshot), so the
      original tail dedupe protection applies unchanged.

    Because provenance is appended in order, the most recent boundary wins:
    entries recorded before a later ``global-dedupe-rebuild`` are history and
    are neither trusted nor range-checked.  ``--base-sources`` may override
    only when it agrees with the resolved boundary.
    """
    n = index['n']
    prov = index.get('provenance') or []

    last_rebuild = None
    for pos, entry in enumerate(prov):
        if isinstance(entry, dict) \
                and entry.get('mergePolicy') == 'global-dedupe-rebuild':
            last_rebuild = pos

    # Only the active lineage is validated: everything at or after the last
    # wholesale rebuild.  Pre-rebuild firstSourceIndex values belong to the
    # lineage the rebuild replaced and are ignored.
    active = prov if last_rebuild is None else prov[last_rebuild + 1:]

    first_idx = None
    for entry in active:
        if not isinstance(entry, dict) or 'firstSourceIndex' not in entry:
            continue
        fi = entry['firstSourceIndex']
        if not isinstance(fi, int) or isinstance(fi, bool) \
                or fi < 0 or fi > n:
            raise ValueError(
                'base provenance firstSourceIndex %r out of range' % fi)
        if first_idx is not None and fi != first_idx:
            raise ValueError(
                'base provenance has conflicting firstSourceIndex: '
                '%r vs %r' % (first_idx, fi))
        first_idx = fi

    if first_idx is not None:
        base_n = first_idx
    elif last_rebuild is not None:
        # rebuild output: the whole index is the candidate set, no tail
        base_n = n
    elif override is not None:
        base_n = override
    else:
        raise ValueError(
            'base index carries no provenance.firstSourceIndex; pass '
            '--base-sources to declare how many leading records are the '
            'original corpus')

    if override is not None and override != base_n:
        raise ValueError(
            '--base-sources %d contradicts provenance boundary %d'
            % (override, base_n))
    if not isinstance(base_n, int) or isinstance(base_n, bool) \
            or not (0 <= base_n <= n):
        raise ValueError('base_sources %r out of range' % base_n)
    return base_n


def reconcile_corpus(base_dir, src_path, stage_dir, report_path=None,
                     merge_source='unknown', base_sources=None, now_ms=None):
    base_dir = os.path.abspath(base_dir)
    src_path = os.path.abspath(src_path)
    stage_dir = os.path.abspath(stage_dir)
    if report_path:
        report_path = os.path.abspath(report_path)

    # ---- path safety: never touch base, never overwrite existing stage,
    # never let report overwrite the input source ----
    base_c = _canon(base_dir)
    stage_c = _canon(stage_dir)
    src_c = _canon(src_path)
    if stage_c == base_c:
        raise ValueError('--stage must differ from --base')
    if _is_within(stage_dir, base_dir):
        raise ValueError('--stage must not be inside --base')
    if stage_c == src_c or _is_within(src_path, stage_dir):
        raise ValueError('--stage must not equal or sit inside --src')
    if _is_within(stage_dir, src_path):
        raise ValueError('--src must not sit inside --stage')
    if os.path.exists(stage_dir) and (
            not os.path.isdir(stage_dir) or os.listdir(stage_dir)):
        raise ValueError('--stage already exists and is not empty: %s'
                         % stage_dir)
    if report_path:
        rep_c = _canon(report_path)
        if rep_c == src_c or _is_within(report_path, src_path) \
                or _is_within(src_path, report_path):
            raise ValueError('--report must not overwrite or contain --src')
        if _is_within(report_path, base_dir):
            raise ValueError('--report must not be inside --base')
        if _is_within(report_path, stage_dir):
            raise ValueError('--report must not be inside --stage')
        if rep_c == stage_c:
            raise ValueError('--report must differ from --stage')

    if now_ms is None:
        now_ms = int(time.time() * 1000)

    base = append_corpus.load_and_validate_base(base_dir)
    index = base['index']
    sid2node = base['sid2node']
    base_n = detect_base_n(index, base_sources)

    with open(src_path, 'r', encoding='utf-8-sig') as f:
        incoming = json.load(f)
    if not isinstance(incoming, list):
        raise ValueError('incoming source file is not a JSON array')
    for pos, item in enumerate(incoming):
        if not isinstance(item, dict):
            raise ValueError('incoming source #%d is not an object' % pos)

    # ---- candidates: base records up to the resolved boundary.  For an
    # append-only base the tail beyond firstSourceIndex is the previous
    # incoming snapshot and must not be counted twice; for a rebuild base the
    # boundary is n, so all records are candidates and there is no tail. ----
    candidates = []
    for i in range(base_n):
        node = sid2node['i%d' % i]
        candidates.append(evaluate_candidate(node, 'base', i, now_ms))
    for j, item in enumerate(incoming):
        candidates.append(evaluate_candidate(item, 'incoming', j, now_ms))

    # sanity: everything appended by the previous run must be a subset of
    # this incoming snapshot's keys (no third-party rows inside the tail).
    # A rebuild base has base_n == n, so this check is vacuously satisfied.
    incoming_keys = {c['key'] for c in candidates if c['side'] == 'incoming'}
    tail_keys = set()
    for i in range(base_n, index['n']):
        node = sid2node['i%d' % i]
        tail_keys.add(append_corpus.identity_key(node.get('bookSourceUrl'),
                                                 node.get('bookSourceType')))
    if not tail_keys <= incoming_keys:
        raise ValueError(
            'base tail (%d records beyond the candidate boundary) contains '
            'keys absent from --src; refusing to guess their provenance'
            % (index['n'] - base_n))

    buckets = {}
    for c in candidates:
        buckets.setdefault(c['key'], []).append(c)

    winners = []           # cleaned/raw records for build_corpus
    audit_keys = {}        # key -> per-key audit record
    stats = {
        'baseSources': base_n,
        'incomingSources': len(incoming),
        'totalCandidates': len(candidates),
        'identityKeys': len(buckets),
        'winnerKeys': 0,
        'reviewKeys': 0,
        'quarantineKeys': 0,
        'overlapKeys': 0,
        'selectedSources': 0,
        'loserCandidates': 0,
        'reviewCandidates': 0,
        'quarantinedCandidates': 0,
        'selectedOldSources': 0,
        'selectedIncomingSources': 0,
        'identicalSubstanceKeys': 0,
        'qualityLevelKeys': 0,
        'timeDecidedKeys': 0,
        'timeDecidedWinnerBySide': {'base': 0, 'incoming': 0},
        'winnerTimeUnknown': 0,
    }

    for key in sorted(buckets.keys()):
        members = buckets[key]
        sides = {m['side'] for m in members}
        if len(sides) > 1:
            stats['overlapKeys'] += 1
        status, winner, reason, notes = decide_bucket(members)
        entry = {
            'status': status,
            'reason': reason,
            'sides': sorted(sides),
            'candidates': [cand_brief(m) for m in
                           sorted(members, key=_ordinal)],
        }
        if status == 'winner':
            stats['winnerKeys'] += 1
            stats['selectedSources'] += 1
            stats['selected%sSources' % (
                'Old' if winner['side'] == 'base' else 'Incoming')] += 1
            winners.append(winner)
            entry['winner'] = {
                'side': winner['side'],
                'index': winner['index'],
                'timeMs': winner['timeMs'],
                'timeFlag': winner['timeFlag'],
                'fp': winner['fingerprint'][:12],
            }
            if reason == 'identical-substance':
                stats['identicalSubstanceKeys'] += 1
            elif reason == 'quality-level-priority':
                stats['qualityLevelKeys'] += 1
            elif reason == 'valid-time-newer':
                stats['timeDecidedKeys'] += 1
                stats['timeDecidedWinnerBySide'][winner['side']] += 1
            if winner['timeMs'] is None:
                stats['winnerTimeUnknown'] += 1
                entry['winner']['freshness'] = 'unknown'
            for m in members:
                if m is winner:
                    continue
                if m['quarantineReason'] is not None:
                    stats['quarantinedCandidates'] += 1
                else:
                    stats['loserCandidates'] += 1
        elif status == 'review':
            stats['reviewKeys'] += 1
            for m in members:
                if m['quarantineReason'] is not None:
                    stats['quarantinedCandidates'] += 1
                else:
                    stats['reviewCandidates'] += 1
        else:
            stats['quarantineKeys'] += 1
            stats['quarantinedCandidates'] += len(members)
        audit_keys[key] = entry

    accounted = (stats['selectedSources'] + stats['loserCandidates']
                 + stats['reviewCandidates'] + stats['quarantinedCandidates'])
    if accounted != stats['totalCandidates']:
        raise AssertionError('candidate accounting broken: %d != %d'
                             % (accounted, stats['totalCandidates']))

    gen_date = date.today().isoformat()
    tmpdir = tempfile.mkdtemp(prefix='reconcile_corpus_')
    try:
        winners_src = os.path.join(tmpdir, 'winners.json')
        with open(winners_src, 'w', encoding='utf-8') as f:
            json.dump([w['record'] for w in winners], f, ensure_ascii=False)

        builder = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               'build_corpus.py')
        stage_preexisted = os.path.exists(stage_dir)
        os.makedirs(stage_dir, exist_ok=True)
        proc = subprocess.run(
            [sys.executable, builder, '--src', winners_src,
             '--out', stage_dir],
            capture_output=True, text=True, encoding='utf-8')
        if proc.returncode != 0:
            raise RuntimeError('build_corpus failed:\n%s\n%s'
                               % (proc.stdout, proc.stderr))

        check = append_corpus.load_and_validate_base(stage_dir)
        stage_index = check['index']
        if stage_index['n'] != len(winners):
            raise AssertionError('stage n=%d != winners=%d'
                                 % (stage_index['n'], len(winners)))

        # every stage record has a unique identity key present in winners
        stage_keys = set()
        for r in stage_index['rec']:
            node = check['sid2node']['i%d' % r['i']]
            k = append_corpus.identity_key(node.get('bookSourceUrl'),
                                           node.get('bookSourceType'))
            if k in stage_keys:
                raise AssertionError('stage still has duplicate key %s'
                                     % k[:120])
            stage_keys.add(k)
        if len(stage_keys) != stats['winnerKeys']:
            raise AssertionError('stage key count mismatch')

        # each winner's cleaned record round-trips through the stage; the
        # reverse map also yields the final rec.i of every incoming winner
        # (indices were re-numbered by the rebuild, so original input
        # positions cannot be reused).
        expected = {}
        expected_side = {}
        for w in winners:
            cleaned = build_corpus.clean_source(w['record'])
            expected[w['key']] = cleaned
            expected_side[w['key']] = w['side']
        selected_new_i = []
        for r in stage_index['rec']:
            node = check['sid2node']['i%d' % r['i']]
            k = append_corpus.identity_key(node.get('bookSourceUrl'),
                                           node.get('bookSourceType'))
            if node != expected.get(k):
                raise AssertionError(
                    'stage node for key %s does not equal winner record'
                    % k[:120])
            if expected_side[k] == 'incoming':
                selected_new_i.append(r['i'])
        if len(selected_new_i) != stats['selectedIncomingSources']:
            raise AssertionError(
                'selectedNewSourceIndices %d != incoming winners %d'
                % (len(selected_new_i), stats['selectedIncomingSources']))
        for i in selected_new_i:
            if not (0 <= i < stage_index['n']):
                raise AssertionError('selected new index out of range')
        stats['selectedNewSourceIndices'] = selected_new_i

        # ---- audit report (offline; never shipped) ----
        audit_sha = None
        if report_path:
            os.makedirs(os.path.dirname(report_path), exist_ok=True)
            report = {
                'generatedAt': datetime.now(timezone.utc).isoformat(),
                'mergePolicy': 'global-dedupe-rebuild',
                'inputs': {
                    'baseIndexSha256': _sha256_file(base['index_path']),
                    'sourceSha256': _sha256_file(src_path),
                    'baseSources': base_n,
                    'incomingSources': len(incoming),
                    'nowMs': now_ms,
                },
                'stats': stats,
                'keys': audit_keys,
            }
            with open(report_path, 'w', encoding='utf-8') as f:
                json.dump(report, f, ensure_ascii=False, indent=1)
            audit_sha = _sha256_file(report_path)

        # ---- provenance: append the rebuild entry, keep history ----
        prov = list(stage_index.get('provenance') or [])
        prov.append({
            'mergePolicy': 'global-dedupe-rebuild',
            'source': merge_source,
            'baseIndexSha256': _sha256_file(base['index_path']),
            'sourceSha256': _sha256_file(src_path),
            'baseSources': base_n,
            'incomingSources': len(incoming),
            'totalCandidates': stats['totalCandidates'],
            'identityKeys': stats['identityKeys'],
            'selectedSources': stats['selectedSources'],
            'selectedOldSources': stats['selectedOldSources'],
            'selectedIncomingSources': stats['selectedIncomingSources'],
            'selectedNewSourceIndices': selected_new_i,
            'reviewKeys': stats['reviewKeys'],
            'quarantinedKeys': stats['quarantineKeys'],
            'auditSha256': audit_sha,
            'generatedDate': gen_date,
        })
        stage_index['provenance'] = prov
        with open(os.path.join(stage_dir, 'index.json'), 'w',
                  encoding='utf-8') as f:
            json.dump(stage_index, f, ensure_ascii=False,
                      separators=(',', ':'))

        # re-validate the final artifact end-to-end
        append_corpus.load_and_validate_base(stage_dir)

        stats['generatedDate'] = gen_date
        stats['auditSha256'] = audit_sha
        stats['stageIndexSha256'] = _sha256_file(
            os.path.join(stage_dir, 'index.json'))
        print(json.dumps(stats, indent=2, ensure_ascii=False))
        return stats
    except Exception:
        if os.path.isdir(stage_dir):
            if stage_preexisted:
                # pre-existing empty dir: clear partial artifacts only
                for entry in os.listdir(stage_dir):
                    p = os.path.join(stage_dir, entry)
                    if os.path.isdir(p):
                        shutil.rmtree(p, ignore_errors=True)
                    else:
                        try:
                            os.remove(p)
                        except OSError:
                            pass
            else:
                shutil.rmtree(stage_dir, ignore_errors=True)
        raise
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def main():
    p = argparse.ArgumentParser(
        description='Global identity-key dedupe + version arbitration: '
                    'rebuild a fresh corpus stage from base + incoming.')
    p.add_argument('--base', required=True,
                   help='current corpus dir (index.json + shards/)')
    p.add_argument('--src', required=True,
                   help='merged bookSource JSON export (array of objects)')
    p.add_argument('--stage', required=True,
                   help='new output dir; must not exist or be empty')
    p.add_argument('--report', default=None,
                   help='offline audit report JSON path (not shipped)')
    p.add_argument('--merge-source', default='unknown',
                   help='public name of the incoming snapshot for provenance')
    p.add_argument('--base-sources', type=int, default=None,
                   help='explicit candidate boundary: how many leading base '
                        'records to reconcile. Optional for rebuild bases '
                        '(all n records) and for append-only bases that carry '
                        'provenance.firstSourceIndex; when given it must '
                        'agree with the resolved boundary')
    args = p.parse_args()
    try:
        reconcile_corpus(args.base, args.src, args.stage, args.report,
                         merge_source=args.merge_source,
                         base_sources=args.base_sources)
    except Exception as e:
        print('reconcile_corpus failed: %s' % e, file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
