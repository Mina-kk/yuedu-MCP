#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build the book-source corpus assets for yuedu-MCP (WP3).

Input : bookSource-merged.json (Legado export, deduped by bookSourceUrl)
Output: index.json
        shards/<fid>.json (or <fid>-0.json, <fid>-1.json if > 1.5MB)
"""

import argparse
import json
import os
import re
import sys
from datetime import date
from urllib.parse import urlparse

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)
from sanitize_corpus import sanitize_source  # noqa: E402

# 白名单字段
ALLOWED_FIELDS = {
    'bookSourceName',
    'bookSourceGroup',
    'bookSourceType',
    'bookSourceUrl',
    'bookUrlPattern',
    'customButton',
    'customOrder',
    'enabled',
    'enabledCookieJar',
    'enabledExplore',
    'eventListener',
    'exploreScreen',
    'exploreUrl',
    'header',
    'lastUpdateTime',
    'loginCheckJs',
    'loginUi',
    'loginUrl',
    'bookSourceComment',
    'concurrentRate',
    'coverDecodeJs',
    'jsLib',
    'respondTime',
    'ruleBookInfo',
    'ruleContent',
    'ruleExplore',
    'ruleReview',
    'ruleSearch',
    'ruleToc',
    'searchUrl',
    'variableComment',
    'weight',
}

# 验证码特征正则
CAPTCHA_RE = re.compile(r'验证码|captcha|searchcode|getVerificationCode', re.IGNORECASE)
# Cloudflare / Turnstile 特征正则
CF_RE = re.compile(r'cloudflare|cf-chl|turnstile|challenge', re.IGNORECASE)

MAX_SHARD_BYTES = 1500 * 1024  # 1.5MB 拆分阈值


def rule_style(rule):
    """提取规则风格: style in {jsoup,json,xpath,js,css,plain,none}"""
    if rule is None:
        return 'none'
    r = str(rule).strip()
    if not r:
        return 'none'
    if r.startswith('$.') or r.startswith('$[') or '@json:' in r:
        return 'json'
    if '@js:' in r or '<js>' in r or r.startswith('js:'):
        return 'js'
    if r.startswith('//') or r.startswith('//*') or '@xpath' in r:
        return 'xpath'
    if '##' in r:
        return 'css'
    if re.search(r'@tag\.|@class\.|@id\.|@text\.|\.@|@@|class\.|id\.', r):
        return 'jsoup'
    return 'plain'


def extract_search_method(search_url):
    """分析 searchUrl 方法：GET/POST/JS"""
    if not search_url:
        return 'GET'
    su = str(search_url)
    if '@js:' in su or '<js>' in su or su.strip().startswith('js:'):
        return 'JS'
    if re.search(r'"method"\s*:\s*"POST"', su, re.IGNORECASE) or ',{"method":"POST"' in su.replace(' ', ''):
        return 'POST'
    return 'GET'


def clean_source(raw):
    """白名单字段 + 脱敏（认证值/私网主机替换为无效占位，防止再引入）。"""
    filtered = {k: v for k, v in raw.items() if k in ALLOWED_FIELDS}
    return sanitize_source(filtered)


def safe_extract_host(url):
    """提取 bookSourceUrl 的 host；对畸形 URL（假 IPv6 括号、[图片] 前缀、嵌 JSON 等）做防御，绝不抛异常。"""
    url = str(url or '').strip()
    if not url:
        return 'invalid'
    # 形如 "[图片]http://x.com" 的带前缀 URL：从第一个 http(s):// 处截取
    m = re.search(r'https?://', url)
    if m and m.start() > 0:
        url = url[m.start():]
    try:
        if url.startswith(('http://', 'https://')):
            host = urlparse(url).netloc.lower()
        elif '://' in url:
            host = urlparse(url).netloc.lower()
        else:
            host = urlparse('http://' + url).netloc.lower()
    except ValueError:
        # urlparse 对畸形括号 URL 抛 Invalid IPv6 URL；退化为字符串切分（括号/路径/参数/userinfo 全切断）
        rest = url.split('://', 1)[-1]
        host = re.split(r'[/?#\[]', rest, 1)[0].lower().split('@')[-1]
    return host or 'invalid'


def extract_features(raw):
    """分析单条源的聚类与位掩码特征。先清洗（白名单+脱敏），再对清洗后的信息建索引。"""
    cleaned = clean_source(raw)
    url = str(cleaned.get('bookSourceUrl', '') or '').strip()
    host = safe_extract_host(url)

    stype = cleaned.get('bookSourceType', 0)
    try:
        stype = int(stype) if stype is not None else 0
    except (TypeError, ValueError):
        # 脏数据防御："" / "abc" / "1.5" 等一律退化为 0，绝不让构建崩
        try:
            stype = int(float(str(stype).strip()))
        except (TypeError, ValueError):
            stype = 0

    su = cleaned.get('searchUrl', '')
    smethod = extract_search_method(su)

    # 规则 style
    rs = cleaned.get('ruleSearch') or {}
    rbi = cleaned.get('ruleBookInfo') or {}
    rtoc = cleaned.get('ruleToc') or {}
    rc = cleaned.get('ruleContent') or {}

    book_list_style = rule_style(rs.get('bookList') if isinstance(rs, dict) else rs)
    book_info_style = rule_style(rbi.get('init') or rbi.get('name') if isinstance(rbi, dict) else rbi)
    toc_style = rule_style(rtoc.get('chapterList') if isinstance(rtoc, dict) else rtoc)
    content_style = rule_style(rc.get('content') if isinstance(rc, dict) else rc)

    # 全文 JSON 用于特征搜索（清洗后，不含认证值/私网主机）
    raw_str = json.dumps(cleaned, ensure_ascii=False)

    uses_js = ('@js' in raw_str) or ('<js>' in raw_str)
    uses_webview = ('webView' in raw_str) or ('webview' in raw_str)

    cluster_key = (
        stype,
        smethod,
        book_list_style,
        book_info_style,
        toc_style,
        content_style,
        uses_js,
        uses_webview,
    )

    # g 位掩码计算:
    # 1: enabledCookieJar
    # 2: 有loginUrl
    # 4: 有loginCheckJs
    # 8: 语料含验证码特征(验证码|captcha|searchcode|getVerificationCode)
    # 16: 含cloudflare/turnstile特征
    # 32: enabled=false
    # 64: 四段rule不全（缺ruleSearch/ruleBookInfo/ruleToc/ruleContent任一）
    flags = 0
    if cleaned.get('enabledCookieJar'):
        flags |= 1
    if cleaned.get('loginUrl'):
        flags |= 2
    if cleaned.get('loginCheckJs'):
        flags |= 4
    if CAPTCHA_RE.search(raw_str):
        flags |= 8
    if CF_RE.search(raw_str):
        flags |= 16
    if cleaned.get('enabled') is False:
        flags |= 32

    has_search = bool(cleaned.get('ruleSearch'))
    has_book_info = bool(cleaned.get('ruleBookInfo'))
    has_toc = bool(cleaned.get('ruleToc'))
    has_content = bool(cleaned.get('ruleContent'))
    is_complete = has_search and has_book_info and has_toc and has_content
    if not is_complete:
        flags |= 64

    cleaned_bytes = len(json.dumps(cleaned, ensure_ascii=False).encode('utf-8'))

    name = str(cleaned.get('bookSourceName', '') or '')

    return {
        'host': host,
        'name': name,
        'type': stype,
        'smethod': smethod,
        'cluster_key': cluster_key,
        'flags': flags,
        'is_complete': is_complete,
        'enabled': cleaned.get('enabled') is not False,
        'cleaned': cleaned,
        'size': cleaned_bytes,
        'url': url,
    }


def main():
    parser = argparse.ArgumentParser(description="Build book-source corpus assets.")
    parser.add_argument('--src', required=True, help="Path to sources.json")
    parser.add_argument('--out', required=True, help="Output assets corpus directory")
    args = parser.parse_args()

    src_path = args.src
    out_dir = args.out
    shards_dir = os.path.join(out_dir, 'shards')
    os.makedirs(shards_dir, exist_ok=True)

    with open(src_path, 'r', encoding='utf-8-sig') as f:
        raw_sources = json.load(f)

    total_n = len(raw_sources)
    print(f"Loaded {total_n} raw sources.")

    # 提取特征
    feats = [extract_features(s) for s in raw_sources]

    # rec 按 domain (host) 升序排序，若 domain 相同按 name 排序，若还相同按 url
    sorted_indices = sorted(range(total_n), key=lambda i: (feats[i]['host'], feats[i]['name'], feats[i]['url']))

    # 聚类族
    cluster_groups = {}
    for i in sorted_indices:
        ck = feats[i]['cluster_key']
        cluster_groups.setdefault(ck, []).append(i)

    # 族按成员数降序编号 f_0001, f_0002...
    sorted_clusters = sorted(cluster_groups.items(), key=lambda x: -len(x[1]))
    fam_id_map = {}
    for idx, (ck, members) in enumerate(sorted_clusters, start=1):
        fid = f"f_{idx:04d}"
        fam_id_map[ck] = fid

    # 为每个源分配 i 下标与 fid
    rec_list = []
    # 族内成员按 rec 顺序收集
    family_members = {fam_id_map[ck]: [] for ck, _ in sorted_clusters}

    for new_idx, old_idx in enumerate(sorted_indices):
        f = feats[old_idx]
        fid = fam_id_map[f['cluster_key']]
        sid = f"i{new_idx}"
        rec_list.append({
            "i": new_idx,
            "d": f['host'],
            "n": f['name'],
            "t": f['type'],
            "f": fid,
            "g": f['flags']
        })
        family_members[fid].append((new_idx, sid, f))

    # 构建分片与 fam meta
    fam_meta = {}
    total_shard_files = 0
    total_shard_bytes = 0
    max_shard_bytes = 0

    for idx, (ck, _) in enumerate(sorted_clusters, start=1):
        fid = f"f_{idx:04d}"
        members = family_members[fid]
        c = len(members)

        # 族内排名选样例（ex）：complete优先 -> enabled优先 -> 体积小优先，最多6个
        ranked_for_ex = sorted(members, key=lambda item: (
            0 if item[2]['is_complete'] else 1,
            0 if item[2]['enabled'] else 1,
            item[2]['size']
        ))
        ex_ids = [item[1] for item in ranked_for_ex[:6]]

        stype, smethod = ck[0], ck[1]

        # 分片内容: ids 与 n 平行，按 rec 顺序
        shard_ids = [item[1] for item in members]
        shard_nodes = [item[2]['cleaned'] for item in members]

        # 检查是否超过 1.5MB 需要拆分
        full_shard_obj = {
            "f": fid,
            "ids": shard_ids,
            "n": shard_nodes
        }
        full_bytes = len(json.dumps(full_shard_obj, ensure_ascii=False).encode('utf-8'))

        if full_bytes <= MAX_SHARD_BYTES:
            shard_filename = f"{fid}.json"
            shard_path = os.path.join(shards_dir, shard_filename)
            with open(shard_path, 'w', encoding='utf-8') as sf:
                json.dump(full_shard_obj, sf, ensure_ascii=False, separators=(',', ':'))
            shard_size = os.path.getsize(shard_path)
            total_shard_bytes += shard_size
            total_shard_files += 1
            if shard_size > max_shard_bytes:
                max_shard_bytes = shard_size
            fam_s = shard_size
        else:
            # 拆分为 <fid>-0.json, <fid>-1.json ...
            # 动态划分
            part_idx = 0
            cur_ids = []
            cur_nodes = []
            cur_part_bytes = 0
            fam_s = 0

            # 按每个 node 的大小估算切分点
            for sid, node in zip(shard_ids, shard_nodes):
                node_bytes = len(json.dumps(node, ensure_ascii=False).encode('utf-8')) + len(sid) + 10
                if cur_ids and (cur_part_bytes + node_bytes > 1200 * 1024):
                    # 写入当前 part
                    part_filename = f"{fid}-{part_idx}.json"
                    part_path = os.path.join(shards_dir, part_filename)
                    part_obj = {
                        "f": fid,
                        "p": part_idx,
                        "ids": cur_ids,
                        "n": cur_nodes
                    }
                    with open(part_path, 'w', encoding='utf-8') as pf:
                        json.dump(part_obj, pf, ensure_ascii=False, separators=(',', ':'))
                    psize = os.path.getsize(part_path)
                    fam_s += psize
                    total_shard_bytes += psize
                    total_shard_files += 1
                    if psize > max_shard_bytes:
                        max_shard_bytes = psize

                    part_idx += 1
                    cur_ids = []
                    cur_nodes = []
                    cur_part_bytes = 0

                cur_ids.append(sid)
                cur_nodes.append(node)
                cur_part_bytes += node_bytes

            if cur_ids:
                part_filename = f"{fid}-{part_idx}.json"
                part_path = os.path.join(shards_dir, part_filename)
                part_obj = {
                    "f": fid,
                    "p": part_idx,
                    "ids": cur_ids,
                    "n": cur_nodes
                }
                with open(part_path, 'w', encoding='utf-8') as pf:
                    json.dump(part_obj, pf, ensure_ascii=False, separators=(',', ':'))
                psize = os.path.getsize(part_path)
                fam_s += psize
                total_shard_bytes += psize
                total_shard_files += 1
                if psize > max_shard_bytes:
                    max_shard_bytes = psize

        fam_meta[fid] = {
            "t": stype,
            "m": smethod,
            "c": c,
            "s": fam_s,
            "ex": ex_ids
        }

    index_obj = {
        "v": 1,
        "n": total_n,
        "gen": date.today().isoformat(),
        "fam": fam_meta,
        "rec": rec_list
    }

    index_path = os.path.join(out_dir, 'index.json')
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index_obj, f, ensure_ascii=False, separators=(',', ':'))

    index_bytes = os.path.getsize(index_path)

    stats = {
        "sources_count": total_n,
        "families_count": len(fam_meta),
        "index_bytes": index_bytes,
        "shard_files": total_shard_files,
        "total_shard_bytes": total_shard_bytes,
        "max_shard_bytes": max_shard_bytes,
    }
    print("Corpus build completed:")
    print(json.dumps(stats, indent=2))


if __name__ == '__main__':
    main()
