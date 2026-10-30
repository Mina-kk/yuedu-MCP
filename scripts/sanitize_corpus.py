#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Minimal-change sanitizer for the book-source corpus.

Replaces live auth material (JWT, Bearer/Authorization/Cookie values,
auth-code headers, query auth params including client_secret/authkey,
URL userinfo) and RFC1918/loopback/link-local IPv4 plus localhost /
*.local/*.lan/*.internal/*.intranet/*.home.arpa URL hosts with invalid
placeholders so shards can ship without spreading third-party
credentials or community LAN addresses. Mixed Cookie values keep
mustache templates and redact static pairs.

Does not execute JS and does not touch the network. Preserves rec i/id,
family id, shard filenames and JSON shape. Recalculates index.fam[*].s
and syncs index.rec[*].d with the sanitized bookSourceUrl host.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import sys
from urllib.parse import unquote_plus, urlparse

# Two or three base64url segments starting with eyJ (unsigned JWT-like + signed).
JWT_RE = re.compile(
    r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]{8,})?"
)
BEARER_RE = re.compile(r"(?i)(bearer\s+)([A-Za-z0-9._\-+=/]{8,})")
IPV4_RE = re.compile(
    r"\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b"
)
# http(s) URL authority: optional userinfo + host[:port] or [IPv6][:port].
URL_AUTHORITY_RE = re.compile(
    r"(?i)(https?://)(?:([^/@:#?\s]+(?::[^/@#?\s]*)?)@)?(\[[^\]]+\]|[^/?#\s]+)"
)
PRIVATE_HOST_SUFFIXES = (
    ".local",
    ".lan",
    ".internal",
    ".intranet",
    ".home.arpa",
)
_COOKIE_ATTRS = {
    "path",
    "domain",
    "expires",
    "max-age",
    "secure",
    "httponly",
    "samesite",
    "partitioned",
    "priority",
}
# One-or-more userinfo segments so http://a@b@c collapses in a single pass.
USERINFO_RE = re.compile(
    r"(?i)(https?://)(?:[^/@:#?]+(?::[^/@#?]*)?@)+"
)

# Placeholders must NOT match JWT_RE, BEARER_RE, or private IPv4.
JWT_PLACEHOLDER = "__REDACTED_JWT__"
BEARER_TOKEN_PLACEHOLDER = "<redacted>"
AUTH_PLACEHOLDER = "<redacted>"
QUERY_PLACEHOLDER = "<redacted>"
PRIVATE_HOST_PLACEHOLDER = "invalid.example"
BEARER_JWT_GLUE_RE = re.compile(r"(?i)bearer" + re.escape(JWT_PLACEHOLDER))

AUTH_HEADER_KEYS = {
    "authorization",
    "cookie",
    "set-cookie",
    "proxy-authorization",
    "x-auth-token",
    "x-access-token",
    "x-api-key",
    "x-token",
    "token",
    "x-novel-token",
    "authentication",
    "auth-token",
    "auth-code",
    "authcode",
    "auth_code",
    "x-auth-code",
    "x-authcode",
    "x-auth_code",
}
_AUTH_CODE_KEY_RE = re.compile(r"(?i)^(?:x[-_])?auth[-_]?code$")

QUERY_AUTH_MIN8 = {
    "token",
    "access_token",
    "accesstoken",
    "refresh_token",
    "id_token",
    "authorization",
    "bearer",
    "jwt",
    "api_key",
    "apikey",
    "api-key",
    "secret",
    "password",
    "passwd",
    "pwd",
    "cookie",
    "sessionid",
    "session_id",
}
QUERY_AUTH_SECRETS = {
    "client_secret",
    "authkey",
    "auth_key",
    "app_secret",
    "appsecret",
}
QUERY_AUTH_MIN8 = QUERY_AUTH_MIN8 | QUERY_AUTH_SECRETS
QUERY_AUTH_MIN16 = {
    "sign",
    "signature",
    "sid",
    "session",
    "auth",
}
QUERY_AUTH_KEYS = QUERY_AUTH_MIN8 | QUERY_AUTH_MIN16
QUERY_AUTH_RE = re.compile(
    r"(?i)([?&])(" + "|".join(re.escape(k) for k in sorted(QUERY_AUTH_KEYS, key=len, reverse=True)) + r")=([^&]*)"
)

_AUTH_KEY_ALT = "|".join(
    re.escape(k) for k in sorted(AUTH_HEADER_KEYS, key=len, reverse=True)
)
RAW_AUTH_LINE_RE = re.compile(
    r"(?im)^((?:%s)\s*:)(\s*)(.+)$" % _AUTH_KEY_ALT
)
JSON_AUTH_KV_RE = re.compile(
    r'(?i)(["\']?(?:%s)["\']?\s*:\s*)(["\'])(.*?)\2' % _AUTH_KEY_ALT
)

_PRIVATE_NETS = (
    ipaddress.IPv4Network("10.0.0.0/8"),
    ipaddress.IPv4Network("172.16.0.0/12"),
    ipaddress.IPv4Network("192.168.0.0/16"),
    ipaddress.IPv4Network("127.0.0.0/8"),
    ipaddress.IPv4Network("169.254.0.0/16"),
)

URLISH_FIELDS = {
    "bookSourceUrl",
    "searchUrl",
    "exploreUrl",
    "loginUrl",
    "bookUrlPattern",
    "coverDecodeJs",
}


class Stats:
    def __init__(self):
        self.jwt = 0
        self.bearer = 0
        self.auth_header = 0
        self.cookie = 0
        self.query_auth = 0
        self.userinfo = 0
        self.private_ip = 0
        self.private_host = 0
        self.nodes_changed = 0
        self.shards_rewritten = 0
        self.rec_d_updated = 0
        self.fam_s_updated = 0
        self.shard_files = 0
        self.nodes_scanned = 0

    def as_dict(self):
        return dict(self.__dict__)


def is_sensitive_ipv4(ip):
    try:
        addr = ipaddress.IPv4Address(ip)
    except (OSError, ValueError):
        return False
    return any(addr in net for net in _PRIVATE_NETS)


def _split_hostport(authority):
    """Return (host, port_suffix) where port_suffix is '' or ':8080'."""
    if not authority:
        return "", ""
    if authority.startswith("["):
        close = authority.find("]")
        if close != -1:
            return authority[: close + 1], authority[close + 1 :]
        return authority, ""
    if ":" in authority:
        host, port = authority.rsplit(":", 1)
        if port.isdigit():
            return host, ":" + port
    return authority, ""


def is_sensitive_hostname(host):
    """True for localhost and RFC6761/special-use community suffixes (host only)."""
    h = str(host or "").strip().lower()
    if not h or h.startswith("["):
        return False
    if ":" in h:
        h, port = h.rsplit(":", 1)
        if not port.isdigit():
            h = h + ":" + port
    if h == "localhost":
        return True
    return any(h.endswith(suf) for suf in PRIVATE_HOST_SUFFIXES)


def is_template_value(s):
    if s is None:
        return True
    t = str(s).strip()
    if not t:
        return True
    if "{{" in t or "}}" in t or "${" in t:
        return True
    if "@js:" in t or "<js>" in t or t.startswith("js:"):
        return True
    if t.lower() in (
        "cookie",
        "token",
        "bearer",
        "authorization",
        "none",
        "null",
        "undefined",
    ):
        return True
    return False


def _already_redacted(s):
    t = str(s).strip()
    if t in (AUTH_PLACEHOLDER, JWT_PLACEHOLDER, QUERY_PLACEHOLDER, BEARER_TOKEN_PLACEHOLDER):
        return True
    if t.lower().startswith("bearer ") and _already_redacted(t[7:]):
        return True
    return False


def _contains_placeholder(s):
    return (
        AUTH_PLACEHOLDER in s
        or JWT_PLACEHOLDER in s
        or QUERY_PLACEHOLDER in s
        or BEARER_TOKEN_PLACEHOLDER in s
    )


def extract_host(url):
    """Same contract as build_corpus.safe_extract_host."""
    url = str(url or "").strip()
    if not url:
        return "invalid"
    m = re.search(r"https?://", url)
    if m and m.start() > 0:
        url = url[m.start():]
    try:
        if url.startswith(("http://", "https://")):
            host = urlparse(url).netloc.lower()
        elif "://" in url:
            host = urlparse(url).netloc.lower()
        else:
            host = urlparse("http://" + url).netloc.lower()
    except ValueError:
        rest = url.split("://", 1)[-1]
        host = re.split(r"[/?#\[]", rest, maxsplit=1)[0].lower().split("@")[-1]
    return host or "invalid"


def _bump(stats, attr, n=1):
    if stats is not None and n:
        setattr(stats, attr, getattr(stats, attr) + n)


def sanitize_string(s, stats=None):
    if not isinstance(s, str) or not s:
        return s

    def jwt_sub(m):
        _bump(stats, "jwt")
        return JWT_PLACEHOLDER

    s = JWT_RE.sub(jwt_sub, s)

    def bearer_sub(m):
        _bump(stats, "bearer")
        return m.group(1) + BEARER_TOKEN_PLACEHOLDER

    s = BEARER_RE.sub(bearer_sub, s)

    def bearer_glue_sub(_m):
        _bump(stats, "bearer")
        return "Bearer " + BEARER_TOKEN_PLACEHOLDER

    s = BEARER_JWT_GLUE_RE.sub(bearer_glue_sub, s)

    def ip_sub(m):
        ip = m.group(0)
        if is_sensitive_ipv4(ip):
            _bump(stats, "private_ip")
            return PRIVATE_HOST_PLACEHOLDER
        return ip

    s = IPV4_RE.sub(ip_sub, s)

    def userinfo_sub(m):
        _bump(stats, "userinfo")
        return m.group(1)

    for _ in range(8):
        s2, n = USERINFO_RE.subn(userinfo_sub, s)
        if n == 0:
            break
        s = s2
    s = _redact_query_auth(s, stats)
    s = _redact_private_hosts(s, stats)
    return s


def _redact_private_hosts(s, stats=None):
    def sub(m):
        scheme, userinfo, authority = m.group(1), m.group(2), m.group(3)
        host, port = _split_hostport(authority)
        if is_sensitive_hostname(host):
            _bump(stats, "private_host")
            ui = (userinfo + "@") if userinfo else ""
            return scheme + ui + PRIVATE_HOST_PLACEHOLDER + port
        return m.group(0)

    return URL_AUTHORITY_RE.sub(sub, s)


def _redact_query_auth(s, stats=None):
    def sub(m):
        prefix, key, val = m.group(1), m.group(2), m.group(3)
        try:
            decoded = unquote_plus(val)
        except Exception:
            decoded = val
        if is_template_value(decoded) or _already_redacted(decoded):
            return m.group(0)
        lk = key.lower()
        if lk in ("password", "passwd", "pwd") or lk in QUERY_AUTH_SECRETS:
            minlen = 1
        elif lk in QUERY_AUTH_MIN16:
            minlen = 16
        else:
            minlen = 8
        if len(decoded) < minlen:
            return m.group(0)
        _bump(stats, "query_auth")
        return prefix + key + "=" + QUERY_PLACEHOLDER

    return QUERY_AUTH_RE.sub(sub, s)


def _redact_secret_value(val, stats=None, cookie=False):
    if not isinstance(val, str) or is_template_value(val) or _already_redacted(val):
        return sanitize_value(val, stats)
    if _contains_placeholder(val):
        return sanitize_string(val, stats)
    _bump(stats, "auth_header")
    if cookie:
        _bump(stats, "cookie")
    cleaned = sanitize_string(val, stats)
    if cleaned != val:
        if BEARER_RE.search(val) or JWT_RE.search(val):
            return cleaned
        return AUTH_PLACEHOLDER
    return AUTH_PLACEHOLDER


def _is_auth_header_key(key):
    lk = str(key).lower()
    if lk in AUTH_HEADER_KEYS:
        return True
    if lk.endswith("-token") or lk.endswith("_token"):
        return True
    if _AUTH_CODE_KEY_RE.match(lk):
        return True
    return False


def _cookie_static_pair_live(part):
    """True if this cookie segment is a non-template, non-attribute name=value."""
    stripped = part.strip()
    if not stripped or is_template_value(stripped):
        return False
    if "=" not in stripped:
        return False
    name, _, rhs = stripped.partition("=")
    lname = name.strip().lower()
    if lname in _COOKIE_ATTRS:
        return False
    val = rhs.strip()
    if not val or is_template_value(val) or _already_redacted(val):
        return False
    return True


def cookie_has_live_static_pair(val):
    if not isinstance(val, str) or not val:
        return False
    if "{{" not in val and "}}" not in val and "${" not in val:
        return False
    return any(_cookie_static_pair_live(p) for p in val.split(";"))


def _redact_cookie_value(val, stats=None):
    if not isinstance(val, str) or not val:
        return val
    if _already_redacted(val):
        return val
    if not is_template_value(val):
        return _redact_secret_value(val, stats, cookie=True)
    parts = val.split(";")
    new_parts = []
    changed = False
    for part in parts:
        if not _cookie_static_pair_live(part):
            new_parts.append(part)
            continue
        stripped = part.strip()
        name, _, _rhs = stripped.partition("=")
        lead = part[: len(part) - len(part.lstrip())]
        new_parts.append(lead + name.strip() + "=" + AUTH_PLACEHOLDER)
        changed = True
    if not changed:
        return sanitize_string(val, stats)
    _bump(stats, "auth_header")
    _bump(stats, "cookie")
    return sanitize_string(";".join(new_parts), stats)


def sanitize_header_dict(obj, stats=None):
    out = {}
    for k, v in obj.items():
        if _is_auth_header_key(k):
            cookie = str(k).lower() in ("cookie", "set-cookie")
            if cookie:
                out[k] = _redact_cookie_value(v, stats)
            else:
                out[k] = _redact_secret_value(v, stats, cookie=False)
        else:
            out[k] = sanitize_value(v, stats)
    return out


def _sanitize_raw_headers(s, stats=None):
    def line_sub(m):
        key, sp, val = m.group(1), m.group(2), m.group(3)
        is_cookie = key.lower().lstrip().startswith("cookie")
        if is_cookie:
            return key + sp + _redact_cookie_value(val, stats)
        if is_template_value(val) or _already_redacted(val):
            return m.group(0)
        _bump(stats, "auth_header")
        return key + sp + AUTH_PLACEHOLDER

    s2 = RAW_AUTH_LINE_RE.sub(line_sub, s)

    def kv_sub(m):
        pre, quote, val = m.group(1), m.group(2), m.group(3)
        if "cookie" in pre.lower():
            return pre + quote + _redact_cookie_value(val, stats) + quote
        if is_template_value(val) or _already_redacted(val):
            return m.group(0)
        _bump(stats, "auth_header")
        return pre + quote + AUTH_PLACEHOLDER + quote

    s2 = JSON_AUTH_KV_RE.sub(kv_sub, s2)
    return sanitize_string(s2, stats)


def sanitize_header_field(val, stats=None):
    if isinstance(val, dict):
        return sanitize_header_dict(val, stats)
    if isinstance(val, list):
        return [sanitize_header_field(x, stats) for x in val]
    if not isinstance(val, str):
        return val
    stripped = val.strip()
    if stripped.startswith("{") and stripped.endswith("}"):
        try:
            parsed = json.loads(val)
        except Exception:
            parsed = None
        if isinstance(parsed, dict):
            new = sanitize_header_dict(parsed, stats)
            if new != parsed:
                return json.dumps(new, ensure_ascii=False, separators=(",", ":"))
            return val
    return _sanitize_raw_headers(val, stats)


def _split_url_options(s):
    idx = s.rfind(",{")
    sep = ",{"
    if idx < 0:
        idx = s.rfind(", {")
        sep = ", {"
    if idx < 0:
        return s, None, None
    blob = s[idx + len(sep) - 1:]
    try:
        opts = json.loads(blob)
    except Exception:
        return s, None, None
    if not isinstance(opts, dict):
        return s, None, None
    return s[:idx], opts, sep


def sanitize_url_options(opts, stats=None):
    out = {}
    for k, v in opts.items():
        lk = str(k).lower()
        if lk in ("headers", "header"):
            out[k] = sanitize_header_field(v, stats)
        elif lk == "body" and isinstance(v, str):
            out[k] = sanitize_string(v, stats)
        else:
            out[k] = sanitize_value(v, stats)
    return out


def sanitize_urlish(s, stats=None):
    if not isinstance(s, str):
        return sanitize_value(s, stats)
    base, opts, sep = _split_url_options(s)
    if opts is None:
        return sanitize_string(s, stats)
    new_base = sanitize_string(base, stats)
    new_opts = sanitize_url_options(opts, stats)
    if new_base == base and new_opts == opts:
        return s
    dumped = json.dumps(new_opts, ensure_ascii=False, separators=(",", ":"))
    return new_base + sep[:-1] + dumped


def sanitize_value(val, stats=None):
    if isinstance(val, dict):
        return {k: sanitize_field(k, v, stats) for k, v in val.items()}
    if isinstance(val, list):
        return [sanitize_value(x, stats) for x in val]
    if isinstance(val, str):
        return sanitize_string(val, stats)
    return val


def sanitize_field(key, val, stats=None):
    if key == "header":
        return sanitize_header_field(val, stats)
    if key in URLISH_FIELDS:
        return sanitize_urlish(val, stats)
    return sanitize_value(val, stats)


def sanitize_source(src, stats=None):
    """Sanitize one book-source object. Preserves key order; unknown types kept."""
    if not isinstance(src, dict):
        return src
    return {k: sanitize_field(k, v, stats) for k, v in src.items()}


def _dump_json(path, obj):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))


def _load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


RESIDUAL_CATS = (
    "jwt",
    "bearer",
    "private_ipv4",
    "private_host",
    "query_secret",
    "auth_code_header",
    "mixed_cookie",
    "bearer_glue",
)


def _empty_residual():
    return {k: 0 for k in RESIDUAL_CATS}


def scan_text_residuals(text):
    """Count residual categories in a string. Never returns match text."""
    jwt = len(JWT_RE.findall(text))
    bearer = len(BEARER_RE.findall(text))
    glue = len(BEARER_JWT_GLUE_RE.findall(text))
    priv = 0
    for m in IPV4_RE.finditer(text):
        if is_sensitive_ipv4(m.group(0)):
            priv += 1
    priv_host = 0
    for m in URL_AUTHORITY_RE.finditer(text):
        host, _port = _split_hostport(m.group(3))
        if is_sensitive_hostname(host):
            priv_host += 1
    qsec = 0
    for m in QUERY_AUTH_RE.finditer(text):
        key, val = m.group(2), m.group(3)
        try:
            decoded = unquote_plus(val)
        except Exception:
            decoded = val
        if is_template_value(decoded) or _already_redacted(decoded):
            continue
        if key.lower() in QUERY_AUTH_SECRETS:
            qsec += 1
    return {
        "jwt": jwt,
        "bearer": bearer,
        "private_ipv4": priv,
        "private_host": priv_host,
        "query_secret": qsec,
        "auth_code_header": 0,
        "mixed_cookie": 0,
        "bearer_glue": glue,
    }


def _scan_header_dict(obj, acc):
    for k, v in obj.items():
        if not isinstance(v, str):
            continue
        lk = str(k).lower()
        if lk in ("cookie", "set-cookie"):
            if cookie_has_live_static_pair(v):
                acc["mixed_cookie"] += 1
        elif _AUTH_CODE_KEY_RE.match(lk):
            if v.strip() and not is_template_value(v) and not _already_redacted(v):
                acc["auth_code_header"] += 1


def scan_obj_residuals(obj, acc=None):
    """Walk a parsed JSON value. Counts only; never stores match text."""
    if acc is None:
        acc = _empty_residual()
    if isinstance(obj, dict):
        _scan_header_dict(obj, acc)
        for v in obj.values():
            scan_obj_residuals(v, acc)
    elif isinstance(obj, list):
        for x in obj:
            scan_obj_residuals(x, acc)
    elif isinstance(obj, str):
        hit = scan_text_residuals(obj)
        for k in (
            "jwt",
            "bearer",
            "private_ipv4",
            "private_host",
            "query_secret",
            "bearer_glue",
        ):
            acc[k] += hit[k]
        st = obj.strip()
        if st.startswith("{") and st.endswith("}"):
            try:
                parsed = json.loads(obj)
            except Exception:
                parsed = None
            if isinstance(parsed, dict):
                _scan_header_dict(parsed, acc)
                for v in parsed.values():
                    if isinstance(v, (dict, list)):
                        scan_obj_residuals(v, acc)
    return acc


def scan_residuals(corpus_dir):
    """Scan index + shards. Returns category counts and file counts only."""
    totals = _empty_residual()
    files_with = _empty_residual()
    index_path = os.path.join(corpus_dir, "index.json")
    shards_dir = os.path.join(corpus_dir, "shards")
    paths = []
    if os.path.isfile(index_path):
        paths.append(index_path)
    if os.path.isdir(shards_dir):
        for fn in os.listdir(shards_dir):
            if fn.endswith(".json"):
                paths.append(os.path.join(shards_dir, fn))
    for path in paths:
        try:
            obj = _load_json(path)
        except Exception:
            with open(path, "r", encoding="utf-8") as f:
                obj = f.read()
        hit = scan_obj_residuals(obj)
        if os.path.basename(path) == "index.json" and isinstance(obj, dict):
            for r in obj.get("rec") or []:
                if isinstance(r, dict) and is_sensitive_hostname(str(r.get("d") or "")):
                    hit["private_host"] += 1
        for k in RESIDUAL_CATS:
            totals[k] += hit[k]
            if hit[k]:
                files_with[k] += 1
    out = dict(totals)
    for k in RESIDUAL_CATS:
        out["files_with_" + k] = files_with[k]
    out["files_scanned"] = len(paths)
    return out


def sanitize_corpus(corpus_dir, dry_run=False):
    """In-place (or dry-run) sanitize of a build_corpus output directory."""
    index_path = os.path.join(corpus_dir, "index.json")
    shards_dir = os.path.join(corpus_dir, "shards")
    if not os.path.isfile(index_path) or not os.path.isdir(shards_dir):
        raise ValueError("corpus dir missing index.json or shards/: %s" % corpus_dir)

    index = _load_json(index_path)
    rec = index.get("rec")
    fam = index.get("fam")
    if not isinstance(rec, list) or not isinstance(fam, dict):
        raise ValueError("index rec/fam have wrong types")

    stats = Stats()
    shard_files = sorted(
        fn for fn in os.listdir(shards_dir)
        if fn.endswith(".json") and os.path.isfile(os.path.join(shards_dir, fn))
    )
    stats.shard_files = len(shard_files)
    fam_bytes = {fid: 0 for fid in fam}

    for fn in shard_files:
        path = os.path.join(shards_dir, fn)
        obj = _load_json(path)
        if not isinstance(obj, dict):
            raise ValueError("shard %s is not an object" % fn)
        ids = obj.get("ids") or []
        nodes = obj.get("n") or []
        if not isinstance(ids, list) or not isinstance(nodes, list):
            raise ValueError("shard %s ids/n not lists" % fn)
        if len(ids) != len(nodes):
            raise ValueError("shard %s ids/n length mismatch" % fn)

        new_nodes = []
        shard_changed = False
        for sid, node in zip(ids, nodes):
            stats.nodes_scanned += 1
            new_node = sanitize_source(node, stats)
            if new_node != node:
                shard_changed = True
                stats.nodes_changed += 1
            new_nodes.append(new_node)
            if isinstance(sid, str) and re.match(r"^i\d+$", sid):
                i = int(sid[1:])
                if 0 <= i < len(rec) and isinstance(new_node, dict):
                    new_d = extract_host(new_node.get("bookSourceUrl"))
                    if rec[i].get("d") != new_d:
                        rec[i]["d"] = new_d
                        stats.rec_d_updated += 1

        obj["n"] = new_nodes
        if shard_changed:
            stats.shards_rewritten += 1
            if not dry_run:
                _dump_json(path, obj)

        if (not dry_run) or (not shard_changed):
            sz = os.path.getsize(path)
        else:
            sz = len(
                json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            )
        fid = obj.get("f")
        if fid in fam_bytes:
            fam_bytes[fid] += sz

    for fid, sz in fam_bytes.items():
        meta = fam.get(fid)
        if not isinstance(meta, dict):
            continue
        if meta.get("s") != sz:
            meta["s"] = sz
            stats.fam_s_updated += 1

    if not dry_run and (stats.rec_d_updated or stats.fam_s_updated):
        _dump_json(index_path, index)

    return stats, index


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Sanitize corpus shards (JWT/auth/private IP) in place."
    )
    parser.add_argument("--corpus", required=True, help="Corpus directory (index.json + shards/)")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Count replacements without writing files",
    )
    args = parser.parse_args(argv)
    stats, _ = sanitize_corpus(args.corpus, dry_run=args.dry_run)
    report = {"dry_run": bool(args.dry_run)}
    report.update(stats.as_dict())
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if not args.dry_run:
        residual = scan_residuals(args.corpus)
        print(json.dumps({"residual": residual}, indent=2, ensure_ascii=False))
        leftover = [k for k in RESIDUAL_CATS if residual.get(k)]
        if leftover:
            print(
                "BLOCKER: residual "
                + " ".join("%s=%d" % (k, residual[k]) for k in leftover)
                + " files "
                + " ".join(
                    "%s=%d" % (k, residual.get("files_with_" + k, 0)) for k in leftover
                ),
                file=sys.stderr,
            )
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
