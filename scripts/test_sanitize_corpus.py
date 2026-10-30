#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""stdlib unittest coverage for sanitize_corpus.py (synthetic samples only)."""

import json
import os
import shutil
import sys
import tempfile
import unittest

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
import append_corpus as ac  # noqa: E402
import build_corpus as bc  # noqa: E402
import sanitize_corpus as sc  # noqa: E402

# Synthetic material — not real credentials. Failure messages report counts only.
SYN_JWT = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJzdWIiOiJzeW50aGV0aWMifQ."
    "synthsignaturepartok"
)
SYN_JWT2 = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzeW50aGV0aWMifQ"
SYN_BEARER = "synthetictokenvalue123"
SYN_PRIV = "192.168.77.88"
SYN_PUB = "8.8.8.8"
SYN_COOKIE = "JSESSIONID=syntheticcookievalue"
SYN_COOKIE_STATIC = "SID=staticsid01"
SYN_AUTH_CODE = "synthauthcodevalue00000000000001"
SYN_CLIENT_SECRET = "synthclientsecret99"
SYN_AUTHKEY = "synthauthkeyvalue1"
NOVEL = "他抬头看向远方，说：今天天气不错。章节内容示例。"
NOVEL_HOST_WORDS = "他走进 local 书店，internal 章节还没写完，lan 只是一个词。"


def _assert_no_residuals(test, blob, label):
    if not isinstance(blob, str):
        blob = json.dumps(blob, ensure_ascii=False)
    hit = sc.scan_text_residuals(blob)
    extra = sc.scan_obj_residuals(blob if not isinstance(blob, str) else _try_json(blob))
    merged = dict(hit)
    for k in ("auth_code_header", "mixed_cookie"):
        merged[k] = extra.get(k, 0) or hit.get(k, 0)
    bad = {k: merged[k] for k in sc.RESIDUAL_CATS if merged.get(k)}
    test.assertFalse(
        bad,
        "%s residual %s"
        % (label, " ".join("%s=%d" % (k, v) for k, v in bad.items())),
    )


def _try_json(blob):
    if isinstance(blob, (dict, list)):
        return blob
    s = str(blob).strip()
    if s.startswith("{") or s.startswith("["):
        try:
            return json.loads(blob)
        except Exception:
            return blob
    return blob


def _fail_if_contains(test, blob, needle, label):
    text = blob if isinstance(blob, str) else json.dumps(blob, ensure_ascii=False)
    if needle in text:
        test.fail("%s still contains live synthetic material" % label)


def _assert_unchanged(test, before, after, label):
    test.assertEqual(before, after, "%s mutated unrelated sample" % label)


def make_source(name, url, extra=None):
    s = {
        "bookSourceName": name,
        "bookSourceUrl": url,
        "bookSourceType": 0,
        "ruleSearch": {"bookList": "$.list", "name": "$.n"},
        "ruleBookInfo": {"init": "$.x", "name": "$.n"},
        "ruleToc": {"chapterList": "$.c"},
        "ruleContent": {"content": "$.t"},
    }
    if extra:
        s.update(extra)
    return s


def write_corpus(src_nodes, out_dir):
    """Hand-build a 1-family corpus (bypass builder so dirty values survive)."""
    shards = os.path.join(out_dir, "shards")
    os.makedirs(shards, exist_ok=True)
    ids = ["i%d" % i for i in range(len(src_nodes))]
    rec = []
    for i, node in enumerate(src_nodes):
        rec.append({
            "i": i,
            "d": sc.extract_host(node.get("bookSourceUrl")),
            "n": node.get("bookSourceName") or "",
            "t": int(node.get("bookSourceType") or 0),
            "f": "f_0001",
            "g": 0,
        })
    shard = {"f": "f_0001", "ids": ids, "n": src_nodes}
    shard_path = os.path.join(shards, "f_0001.json")
    with open(shard_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(shard, f, ensure_ascii=False, separators=(",", ":"))
    index = {
        "v": 1,
        "n": len(src_nodes),
        "gen": "2026-01-01",
        "fam": {
            "f_0001": {
                "t": 0,
                "m": "GET",
                "c": len(src_nodes),
                "s": os.path.getsize(shard_path),
                "ex": ids[:6],
            }
        },
        "rec": rec,
    }
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(index, f, ensure_ascii=False, separators=(",", ":"))
    return out_dir


class SanitizeStringTest(unittest.TestCase):
    def test_jwt_replaced_and_not_residual(self):
        out = sc.sanitize_string("prefix %s suffix" % SYN_JWT)
        self.assertNotEqual(out, "prefix %s suffix" % SYN_JWT)
        self.assertIn(sc.JWT_PLACEHOLDER, out)
        _assert_no_residuals(self, out, "jwt")

    def test_bearer_value_redacted_template_kept(self):
        dirty = "Bearer %s" % SYN_BEARER
        out = sc.sanitize_string(dirty)
        self.assertIn("Bearer " + sc.BEARER_TOKEN_PLACEHOLDER, out)
        _assert_no_residuals(self, out, "bearer")
        tmpl = "Authorization: Bearer {{token}}"
        _assert_unchanged(self, tmpl, sc.sanitize_string(tmpl), "bearer-template")

    def test_private_ip_replaced_public_kept(self):
        out = sc.sanitize_string("http://%s:8080/a http://%s/b" % (SYN_PRIV, SYN_PUB))
        self.assertIn(sc.PRIVATE_HOST_PLACEHOLDER, out)
        self.assertIn(SYN_PUB, out)
        _assert_no_residuals(self, out, "ip")

    def test_query_auth_literal_vs_template(self):
        dirty = "https://ex.example/s?token=syntokenvalue999&q=1"
        out = sc.sanitize_string(dirty)
        self.assertIn("token=" + sc.QUERY_PLACEHOLDER, out)
        tmpl = "https://ex.example/s?token={{key}}&q=1"
        _assert_unchanged(self, tmpl, sc.sanitize_string(tmpl), "query-template")

    def test_userinfo_stripped(self):
        dirty = "https://alice:synthpass@ex.example/path"
        out = sc.sanitize_string(dirty)
        self.assertEqual(out, "https://ex.example/path")
        nested = "https://a@b@ex.example/path"
        out2 = sc.sanitize_string(nested)
        self.assertEqual(out2, "https://ex.example/path")
        self.assertEqual(sc.sanitize_string(out2), out2)

    def test_novel_text_untouched(self):
        _assert_unchanged(self, NOVEL, sc.sanitize_string(NOVEL), "novel")

    def test_short_sign_and_eyj_alone_untouched(self):
        short = "https://ex.example/s?sign=ab"
        _assert_unchanged(self, short, sc.sanitize_string(short), "short-sign")
        lone = "payload starts eyJ but not a jwt"
        _assert_unchanged(self, lone, sc.sanitize_string(lone), "eyj-alone")

    def test_jwt_two_part_replaced(self):
        out = sc.sanitize_string("prefix %s suffix" % SYN_JWT2)
        self.assertIn(sc.JWT_PLACEHOLDER, out)
        _fail_if_contains(self, out, SYN_JWT2, "jwt2")
        _assert_no_residuals(self, out, "jwt2")

    def test_bearer_glued_to_jwt_normalized(self):
        dirty = "Bearer" + SYN_JWT
        out = sc.sanitize_string(dirty)
        self.assertIn("Bearer " + sc.BEARER_TOKEN_PLACEHOLDER, out)
        _fail_if_contains(self, out, SYN_JWT, "bearer-glue")
        _assert_no_residuals(self, out, "bearer-glue")
        ident = "const bearerTokenValue = 1;"
        _assert_unchanged(self, ident, sc.sanitize_string(ident), "bearer-ident")

    def test_query_secret_keys_literal_vs_template(self):
        cases = (
            "client_secret",
            "authkey",
            "auth_key",
            "app_secret",
            "appsecret",
        )
        for key in cases:
            dirty = "https://ex.example/cb?%s=%s&q=1" % (key, SYN_CLIENT_SECRET)
            out = sc.sanitize_string(dirty)
            self.assertIn(key + "=" + sc.QUERY_PLACEHOLDER, out, "query-%s-placeholder" % key)
            _fail_if_contains(self, out, SYN_CLIENT_SECRET, "query-%s" % key)
            tmpl = "https://ex.example/cb?%s={{secret}}&q=1" % key
            _assert_unchanged(self, tmpl, sc.sanitize_string(tmpl), "query-%s-template" % key)
        as_value = "https://ex.example/s?q=client_secret"
        _assert_unchanged(self, as_value, sc.sanitize_string(as_value), "query-secret-as-value")

    def test_private_hostname_url_only(self):
        samples = (
            "https://library.local/read",
            "http://localhost:3000/x",
            "http://nas.lan/a",
            "http://git.internal/a",
            "http://corp.intranet/a",
            "http://gw.home.arpa/a",
        )
        for dirty in samples:
            out = sc.sanitize_string(dirty)
            self.assertIn(sc.PRIVATE_HOST_PLACEHOLDER, out, "host-placeholder")
            _assert_no_residuals(self, out, "priv-host")
            self.assertEqual(sc.sanitize_string(out), out, "host-idempotent")
        self.assertIn(":3000", sc.sanitize_string("http://localhost:3000/x"))
        negatives = (
            NOVEL_HOST_WORDS,
            "https://example.com/local/internal",
            "https://localhost.example.com/a",
            "https://notlocal.example/a",
            "library.local/no-scheme",
            "see localhost for details",
        )
        for clean in negatives:
            _assert_unchanged(self, clean, sc.sanitize_string(clean), "host-negative")


class SanitizeHeaderAndUrlOptionsTest(unittest.TestCase):
    def test_json_header_authorization_jwt(self):
        header = json.dumps(
            {"Authorization": "Bearer " + SYN_JWT, "User-Agent": "ok/1.0"},
            ensure_ascii=False,
        )
        out = sc.sanitize_header_field(header)
        parsed = json.loads(out)
        self.assertEqual(parsed.get("User-Agent"), "ok/1.0")
        _assert_no_residuals(self, out, "json-header")
        self.assertNotEqual(parsed.get("Authorization"), "Bearer " + SYN_JWT)

    def test_json_header_cookie(self):
        header = json.dumps({"Cookie": SYN_COOKIE, "Accept": "*/*"})
        out = sc.sanitize_header_field(header)
        parsed = json.loads(out)
        self.assertEqual(parsed.get("Accept"), "*/*")
        self.assertEqual(parsed.get("Cookie"), sc.AUTH_PLACEHOLDER)

    def test_json_header_template_cookie_kept(self):
        header = json.dumps({"Cookie": "{{cookie}}", "Accept": "*/*"})
        out = sc.sanitize_header_field(header)
        self.assertEqual(out, header)

    def test_json_header_auth_code_redacted_status_kept(self):
        header = json.dumps({
            "auth-code": SYN_AUTH_CODE,
            "status-code": "200",
            "User-Agent": "ok/1.0",
        })
        out = sc.sanitize_header_field(header)
        parsed = json.loads(out)
        self.assertEqual(parsed.get("status-code"), "200")
        self.assertEqual(parsed.get("User-Agent"), "ok/1.0")
        self.assertEqual(parsed.get("auth-code"), sc.AUTH_PLACEHOLDER)
        _fail_if_contains(self, out, SYN_AUTH_CODE, "auth-code")
        tmpl = json.dumps({"authcode": "{{code}}", "Accept": "*/*"})
        self.assertEqual(sc.sanitize_header_field(tmpl), tmpl)
        empty = json.dumps({"auth-code": "", "Accept": "*/*"})
        self.assertEqual(sc.sanitize_header_field(empty), empty)

    def test_json_header_mixed_cookie_static_pair(self):
        mixed = "{{cookie}}; " + SYN_COOKIE_STATIC
        header = json.dumps({"Cookie": mixed, "Accept": "*/*"})
        out = sc.sanitize_header_field(header)
        parsed = json.loads(out)
        self.assertEqual(parsed.get("Accept"), "*/*")
        cookie = parsed.get("Cookie")
        self.assertIn("{{cookie}}", cookie)
        self.assertIn(sc.AUTH_PLACEHOLDER, cookie)
        _fail_if_contains(self, cookie, "staticsid01", "mixed-cookie")
        once = sc.sanitize_header_field(out)
        self.assertEqual(once, out)
        pure_pairs = json.dumps({"Cookie": "token={{token}}; id={{id}}"})
        self.assertEqual(sc.sanitize_header_field(pure_pairs), pure_pairs)
        pure_moustache = json.dumps({"Cookie": "{{cookie}}"})
        self.assertEqual(sc.sanitize_header_field(pure_moustache), pure_moustache)

    def test_json_header_mixed_placeholder_idempotent(self):
        header = json.dumps({
            "Authorization": "prefix %s suffix" % sc.JWT_PLACEHOLDER,
            "User-Agent": "ok/1.0",
        })
        once = sc.sanitize_header_field(header)
        twice = sc.sanitize_header_field(once)
        self.assertEqual(once, twice)
        parsed = json.loads(once)
        self.assertEqual(parsed.get("User-Agent"), "ok/1.0")
        _assert_no_residuals(self, once, "mixed-placeholder")

    def test_raw_header_lines(self):
        raw = "Authorization: Bearer %s\nUser-Agent: ok/1.0" % SYN_BEARER
        out = sc.sanitize_header_field(raw)
        self.assertIn("User-Agent: ok/1.0", out)
        _assert_no_residuals(self, out, "raw-header")

    def test_url_options_headers(self):
        su = (
            'https://ex.example/search?q={{key}},{"method":"POST",'
            '"headers":{"Authorization":"Bearer %s"}}' % SYN_BEARER
        )
        out = sc.sanitize_urlish(su)
        self.assertTrue(out.startswith("https://ex.example/search?q={{key}}"))
        self.assertIn('"method":"POST"', out)
        _assert_no_residuals(self, out, "url-options")

    def test_unrelated_rule_untouched(self):
        src = make_source("甲站", "https://novel.example/read", extra={
            "ruleContent": {"content": NOVEL, "replaceRegex": "##广告##"},
            "header": json.dumps({"User-Agent": "ok/1.0"}),
        })
        out = sc.sanitize_source(src)
        self.assertEqual(out["ruleContent"], src["ruleContent"])
        self.assertEqual(out["header"], src["header"])
        self.assertEqual(out["bookSourceUrl"], src["bookSourceUrl"])


class CorpusRoundtripTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_dry_run_does_not_write(self):
        nodes = [
            make_source("甲", "https://a.example/", extra={
                "header": json.dumps({"Authorization": "Bearer " + SYN_JWT}),
            }),
            make_source("乙", "http://%s/" % SYN_PRIV),
        ]
        corpus = write_corpus(nodes, os.path.join(self.tmp, "c"))
        before = {}
        for dirpath, _, files in os.walk(corpus):
            for fn in files:
                p = os.path.join(dirpath, fn)
                with open(p, "rb") as f:
                    before[os.path.relpath(p, corpus)] = f.read()
        stats, _ = sc.sanitize_corpus(corpus, dry_run=True)
        self.assertGreater(stats.jwt, 0)
        self.assertGreater(stats.private_ip, 0)
        self.assertGreater(stats.nodes_changed, 0)
        after = {}
        for dirpath, _, files in os.walk(corpus):
            for fn in files:
                p = os.path.join(dirpath, fn)
                with open(p, "rb") as f:
                    after[os.path.relpath(p, corpus)] = f.read()
        self.assertEqual(before, after)

    def test_apply_preserves_shape_and_syncs_index(self):
        nodes = [
            make_source("甲", "https://a.example/", extra={
                "header": json.dumps({"Authorization": "Bearer " + SYN_JWT}),
                "ruleToc": {"chapterList": "@js:token=" + SYN_JWT},
            }),
            make_source("乙", "http://%s:8080/reader" % SYN_PRIV),
            make_source("丙", "https://alice:synthpass@b.example/x"),
            make_source("丁", "https://clean.example/", extra={
                "ruleContent": {"content": NOVEL},
            }),
        ]
        corpus = write_corpus(nodes, os.path.join(self.tmp, "c2"))
        n_before = len(os.listdir(os.path.join(corpus, "shards")))
        stats, index = sc.sanitize_corpus(corpus, dry_run=False)
        self.assertEqual(index["n"], 4)
        self.assertEqual(len(index["rec"]), 4)
        self.assertEqual([r["i"] for r in index["rec"]], [0, 1, 2, 3])
        self.assertEqual([r["f"] for r in index["rec"]], ["f_0001"] * 4)
        self.assertEqual(len(os.listdir(os.path.join(corpus, "shards"))), n_before)
        self.assertEqual(index["rec"][0]["d"], "a.example")
        self.assertEqual(index["rec"][1]["d"], sc.PRIVATE_HOST_PLACEHOLDER + ":8080")
        self.assertEqual(index["rec"][2]["d"], "b.example")
        self.assertEqual(index["rec"][3]["d"], "clean.example")
        shard = os.path.join(corpus, "shards", "f_0001.json")
        self.assertEqual(index["fam"]["f_0001"]["s"], os.path.getsize(shard))
        with open(shard, encoding="utf-8") as f:
            obj = json.load(f)
        self.assertEqual(obj["f"], "f_0001")
        self.assertEqual(obj["ids"], ["i0", "i1", "i2", "i3"])
        self.assertNotIn("p", obj)
        self.assertEqual(obj["n"][3]["ruleContent"]["content"], NOVEL)
        _assert_no_residuals(self, obj, "shard")
        residual = sc.scan_residuals(corpus)
        self.assertEqual(residual["jwt"], 0)
        self.assertEqual(residual["bearer"], 0)
        self.assertEqual(residual["private_ipv4"], 0)
        ac.load_and_validate_base(corpus)
        stats2, _ = sc.sanitize_corpus(corpus, dry_run=True)
        self.assertEqual(stats2.nodes_changed, 0)
        self.assertEqual(stats2.jwt, 0)
        self.assertEqual(stats2.bearer, 0)
        self.assertEqual(stats2.private_ip, 0)
        self.assertEqual(stats2.private_host, 0)
        self.assertGreater(stats.nodes_changed, 0)

    def test_blocker_classes_redacted_and_index_synced(self):
        nodes = [
            make_source("甲", "https://library.local:8443/reader", extra={
                "header": json.dumps({
                    "auth-code": SYN_AUTH_CODE,
                    "Cookie": "{{cookie}}; " + SYN_COOKIE_STATIC,
                    "User-Agent": "ok/1.0",
                }),
                "loginUrl": "https://ex.example/cb?client_secret=%s&authkey=%s" % (
                    SYN_CLIENT_SECRET, SYN_AUTHKEY,
                ),
                "loginUi": "https://ex.example/ui?app_secret=%s" % SYN_CLIENT_SECRET,
                "ruleContent": {"content": NOVEL_HOST_WORDS},
            }),
            make_source("乙", "https://clean.example/", extra={
                "header": json.dumps({"authcode": "{{code}}", "Cookie": "{{cookie}}"}),
                "searchUrl": "https://clean.example/s?client_secret={{secret}}",
                "ruleContent": {"content": NOVEL},
            }),
        ]
        corpus = write_corpus(nodes, os.path.join(self.tmp, "c3"))
        n_before = len(os.listdir(os.path.join(corpus, "shards")))
        stats, index = sc.sanitize_corpus(corpus, dry_run=False)
        self.assertEqual(index["n"], 2)
        self.assertEqual([r["i"] for r in index["rec"]], [0, 1])
        self.assertEqual(len(os.listdir(os.path.join(corpus, "shards"))), n_before)
        self.assertEqual(index["rec"][0]["d"], sc.PRIVATE_HOST_PLACEHOLDER + ":8443")
        self.assertEqual(index["rec"][1]["d"], "clean.example")
        shard = os.path.join(corpus, "shards", "f_0001.json")
        self.assertEqual(index["fam"]["f_0001"]["s"], os.path.getsize(shard))
        with open(shard, encoding="utf-8") as f:
            obj = json.load(f)
        self.assertEqual(obj["ids"], ["i0", "i1"])
        hdr = json.loads(obj["n"][0]["header"])
        self.assertEqual(hdr.get("auth-code"), sc.AUTH_PLACEHOLDER)
        self.assertEqual(hdr.get("User-Agent"), "ok/1.0")
        self.assertIn("{{cookie}}", hdr.get("Cookie"))
        _fail_if_contains(self, obj, SYN_AUTH_CODE, "corpus-auth-code")
        _fail_if_contains(self, obj, SYN_CLIENT_SECRET, "corpus-client-secret")
        _fail_if_contains(self, obj, SYN_AUTHKEY, "corpus-authkey")
        _fail_if_contains(self, obj, "staticsid01", "corpus-mixed-cookie")
        self.assertEqual(obj["n"][0]["ruleContent"]["content"], NOVEL_HOST_WORDS)
        self.assertEqual(json.loads(obj["n"][1]["header"]).get("Cookie"), "{{cookie}}")
        self.assertIn("client_secret={{secret}}", obj["n"][1]["searchUrl"])
        residual = sc.scan_residuals(corpus)
        for k in sc.RESIDUAL_CATS:
            self.assertEqual(residual[k], 0, "corpus residual %s=%d" % (k, residual[k]))
        ac.load_and_validate_base(corpus)
        stats2, _ = sc.sanitize_corpus(corpus, dry_run=True)
        self.assertEqual(stats2.nodes_changed, 0)
        self.assertGreater(stats.nodes_changed, 0)
        self.assertGreater(stats.private_host, 0)
        self.assertGreater(stats.query_auth, 0)
        self.assertGreater(stats.cookie, 0)

    def test_load_and_validate_after_builder(self):
        src = [
            make_source("甲", "https://a.example/", extra={
                "header": json.dumps({"Authorization": "Bearer " + SYN_JWT}),
                "loginUrl": "https://a.example/login",
            }),
            make_source("乙", "http://%s/" % SYN_PRIV),
        ]
        src_path = os.path.join(self.tmp, "src.json")
        out = os.path.join(self.tmp, "built")
        os.makedirs(os.path.join(out, "shards"))
        with open(src_path, "w", encoding="utf-8") as f:
            json.dump(src, f, ensure_ascii=False)
        argv_bak = sys.argv
        try:
            sys.argv = ["build_corpus.py", "--src", src_path, "--out", out]
            bc.main()
        finally:
            sys.argv = argv_bak
        base = ac.load_and_validate_base(out)
        self.assertEqual(base["index"]["n"], 2)
        residual = sc.scan_residuals(out)
        self.assertEqual(residual["jwt"], 0)
        self.assertEqual(residual["bearer"], 0)
        self.assertEqual(residual["private_ipv4"], 0)
        hosts = sorted(r["d"] for r in base["index"]["rec"])
        self.assertEqual(hosts, ["a.example", sc.PRIVATE_HOST_PLACEHOLDER])


class BuilderCleanSourceTest(unittest.TestCase):
    def test_clean_source_drops_unknown_and_redacts(self):
        raw = make_source("甲", "http://%s/" % SYN_PRIV, extra={
            "header": json.dumps({"Authorization": "Bearer " + SYN_JWT}),
            "notAField": "secret-should-drop",
        })
        cleaned = bc.clean_source(raw)
        self.assertNotIn("notAField", cleaned)
        self.assertIn("bookSourceUrl", cleaned)
        _assert_no_residuals(self, cleaned, "clean_source")
        self.assertIn(sc.PRIVATE_HOST_PLACEHOLDER, cleaned["bookSourceUrl"])

    def test_extract_features_indexes_cleaned_host(self):
        raw = make_source("甲", "http://%s:9/" % SYN_PRIV, extra={
            "header": json.dumps({"Cookie": SYN_COOKIE}),
        })
        feat = bc.extract_features(raw)
        self.assertTrue(feat["host"].startswith(sc.PRIVATE_HOST_PLACEHOLDER))
        _assert_no_residuals(self, feat["cleaned"], "extract_features")
        self.assertEqual(feat["url"], feat["cleaned"]["bookSourceUrl"])


class ResidualScanDoesNotLeak(unittest.TestCase):
    def test_scan_returns_counts_only(self):
        blob = "x %s y Bearer %s z %s" % (SYN_JWT, SYN_BEARER, SYN_PRIV)
        hit = sc.scan_text_residuals(blob)
        self.assertEqual(set(hit), set(sc.RESIDUAL_CATS))
        self.assertGreater(hit["jwt"], 0)
        self.assertGreater(hit["bearer"], 0)
        self.assertGreater(hit["private_ipv4"], 0)
        dumped = json.dumps(hit)
        self.assertNotIn("eyJ", dumped)
        self.assertNotIn(SYN_PRIV, dumped)
        self.assertNotIn(SYN_BEARER, dumped)
        self.assertNotIn(SYN_AUTH_CODE, dumped)


if __name__ == "__main__":
    unittest.main()
