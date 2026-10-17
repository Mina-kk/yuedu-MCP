package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.RhinoEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoEvaluatorTest {
    @Test fun executesOfficialRhinoAndCapturesLog() {
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.log('hello'); 1 + 2")
        assertEquals("3.0", result.value)
        assertEquals(listOf("hello"), result.logs)
    }

    @Test fun exposesCommonLegadoJavaHelpers() {
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.hexDecodeToString(java.hexEncodeToString('中文')) + ':' + String(java.sha256Encode('x')).length")
        assertEquals("中文:64", result.value)
    }

    @Test fun supportsOfficialBase64ApisIncludingByteArray() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val bytes = evaluator.evaluate("java.bytesToStr(java.base64DecodeToByteArray('aGk='))")
        assertEquals("hi", bytes.value)
        val decoded = evaluator.evaluate("java.base64Decode('aGk=', 'UTF-8')")
        assertEquals("hi", decoded.value)
    }

    @Test fun supportsSingleArgumentEncodeUri() {
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.encodeURI('a b&c')")
        assertEquals("a%20b%26c", result.value)
    }

    @Test fun supportsOfficialThreadSleepUnderJavaLang() {
        val started = System.currentTimeMillis()
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.lang.Thread.sleep(120); 'slept'")
        assertEquals("slept", result.value)
        assertTrue(System.currentTimeMillis() - started >= 100)
    }

    @Test fun symmetricCryptoStringKeysRoundTrip() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val encrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/CBC/PKCS5Padding', java.strToBytes('0123456789abcdef'), java.strToBytes('abcdef9876543210')).encryptBase64('你好')"
        )
        val decrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/CBC/PKCS5Padding', '0123456789abcdef', 'abcdef9876543210').decryptStr('" + encrypted.value + "')"
        )
        assertEquals("你好", decrypted.value)
    }

    @Test fun symmetricCryptoEcbPkcs7AliasRoundTrip() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val encrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/Pkcs7Padding', '0123456789abcdef').encryptBase64('hi')"
        )
        val decrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/PKCS5Padding', java.strToBytes('0123456789abcdef')).decryptStr('" + encrypted.value + "')"
        )
        assertEquals("hi", decrypted.value)
    }

    /** String key/iv 按 UTF-8 取字节，与 strToBytes 同字节 → 等价；与 NIST 二进制 key 不同 → 结果不同。 */
    @Test fun symmetricCryptoKeyIvStringAndByteArrayFormsEquivalent() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val js = """
            var pt = java.hexDecodeToByteArray('6bc1bee22e409f96e93d7e117393172a');
            var byStr = java.createSymmetricCrypto('AES/CBC/NoPadding', '0123456789abcdef', '0123456789abcdef')
                .encryptHex(pt);
            var byStrBytes = java.createSymmetricCrypto('AES/CBC/NoPadding', java.strToBytes('0123456789abcdef'), java.strToBytes('0123456789abcdef'))
                .encryptHex(pt);
            var byNist = java.createSymmetricCrypto('AES/CBC/NoPadding',
                java.hexDecodeToByteArray('2b7e151628aed2a6abf7158809cf4f3c'),
                java.hexDecodeToByteArray('000102030405060708090a0b0c0d0e0f'))
                .encryptHex(pt);
            byStr + '|' + byStrBytes + '|' + byNist + '|' + (byStr == byNist)
        """.trimIndent()
        val result = evaluator.evaluate(js)
        val parts = result.value!!.split('|')
        assertEquals(parts[0], parts[1])                                    // String 与 strToBytes 等价
        assertEquals("7649abac8119b246cee98e9b12e9197d", parts[2])            // NIST CBC 首块
        assertEquals("false", parts[3])                                     // ASCII key ≠ NIST 二进制 key
    }

    /** NIST SP800-38A F.2.2 AES-128 CBC：bytes 进、hex/base64 出、String/ByteArray decryptStr 一致。 */
    @Test fun symmetricCryptoAes128CbcMatchesPublishedNistVector() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val js = """
            var keyBytes = java.hexDecodeToByteArray('2b7e151628aed2a6abf7158809cf4f3c');
            var ivBytes = java.hexDecodeToByteArray('000102030405060708090a0b0c0d0e0f');
            var ptBytes = java.hexDecodeToByteArray('6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710');
            var c = java.createSymmetricCrypto('AES/CBC/NoPadding', keyBytes, ivBytes);
            var hexOut = c.encryptHex(ptBytes);
            var b64Out = c.encryptBase64(ptBytes);
            var decFromBytes = c.decryptStr(java.hexDecodeToByteArray(hexOut));
            var decFromB64 = c.decryptStr(b64Out);
            var plainBytes = c.decrypt(java.hexDecodeToByteArray('7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b273bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7'));
            var plainHex = '';
            for (var i = 0; i < plainBytes.length; i++) plainHex += ('0' + (plainBytes[i] & 255).toString(16)).slice(-2);
            hexOut + '|' + b64Out + '|' + (decFromBytes == decFromB64) + '|' + plainHex
        """.trimIndent()
        val result = evaluator.evaluate(js)
        assertEquals(
            "7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b273bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7" +
                "|dkmrrIEZskbO6Y6bEukZfVCGy5tQchnuldsROpF2eLJzvta448F0O3EW5p4iIpUWP/HKoWgfrAkSDsowdYbhpw==|true" +
                "|6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
            result.value,
        )
    }

    /** NIST SP800-38A F.1.3 AES-128 ECB NoPadding 文献向量。 */
    @Test fun symmetricCryptoAes128EcbMatchesPublishedNistVector() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val hexOut = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/NoPadding', java.hexDecodeToByteArray('2b7e151628aed2a6abf7158809cf4f3c'))" +
                ".encryptHex(java.hexDecodeToByteArray('6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710'))"
        )
        assertEquals(
            "3ad77bb40d7a3660a89ecaf32466ef97f5d3d58503b9699de785895a96fdbaaf43b1cd7f598ece23881b00e3ed0306887b0c785e27e8ad3f8223207104725dd4",
            hexOut.value,
        )
        val b64Out = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/NoPadding', java.hexDecodeToByteArray('2b7e151628aed2a6abf7158809cf4f3c'))" +
                ".encryptBase64(java.hexDecodeToByteArray('6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710'))"
        )
        assertEquals(
            "Otd7tA16NmConsrzJGbvl/XT1YUDuWmd54WJWpb9uq9Dsc1/WY7OI4gbAOPtAwaIewx4XiforT+CIyBxBHJd1A==",
            b64Out.value,
        )
        // encryptBase64Str 别名委托 encryptBase64(String)：UTF-8 明文 → 同一密文
        val alias = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/PKCS5Padding', '0123456789abcdef')" +
                ".encryptBase64Str('中文别名验证')"
        )
        val strForm = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/PKCS5Padding', '0123456789abcdef')" +
                ".encryptBase64('中文别名验证')"
        )
        assertEquals(strForm.value, alias.value)
        // String 与 ByteArray 明文等价：同一份 UTF-8 字节两种重载产出同一密文
        val strEqBytes = evaluator.evaluate(
            "var c=java.createSymmetricCrypto('AES/ECB/PKCS5Padding','0123456789abcdef');" +
                "c.encryptBase64('中文重载等价') == c.encryptBase64(java.strToBytes('中文重载等价'))"
        )
        assertEquals("true", strEqBytes.value)
        // UTF-8 中文经 String 重载加密后，ByteArray decryptStr 解密回原文
        val zhHex = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/CBC/PKCS5Padding', java.hexDecodeToByteArray('2b7e151628aed2a6abf7158809cf4f3c'), java.hexDecodeToByteArray('000102030405060708090a0b0c0d0e0f'))" +
                ".encryptHex('中文四块测试abc')"
        )
        val zhBack = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/CBC/PKCS5Padding', java.hexDecodeToByteArray('2b7e151628aed2a6abf7158809cf4f3c'), java.hexDecodeToByteArray('000102030405060708090a0b0c0d0e0f'))" +
                ".decryptStr(java.hexDecodeToByteArray('" + zhHex.value + "'))"
        )
        assertEquals("中文四块测试abc", zhBack.value)
    }

    @Test fun decryptStrRejectsNonBase64WithSanitizedMessage() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val secretCipher = "???not-base64???"
        val secretKey = "s3cr3tK3y-0123456"
        val secretIv = "s3cr3tIv-6543210"
        val bindings = mapOf("secCipher" to secretCipher, "secKey" to secretKey, "secIv" to secretIv)

        fun assertSanitized(block: () -> Unit) {
            val error = runCatching(block).exceptionOrNull()
            assertTrue("decryptStr 应抛错", error is com.script.ScriptException)
            assertTrue("应保留脚本行号", (error as com.script.ScriptException).lineNumber > 0)
            assertEquals(null, error.cause)
            val text = error.stackTraceToString()
            assertTrue(text.contains("decryptStr：密文必须是合法的标准 Base64 字符串"))
            for (secret in listOf(secretCipher, secretKey, secretIv, "createSymmetricCrypto", "doDec")) {
                assertFalse("错误不应回显输入或源码：$secret", text.contains(secret))
            }
            assertFalse(text.contains("Illegal base64"))
        }

        assertSanitized {
            evaluator.evaluate(
                "var c = java.createSymmetricCrypto('AES/CBC/PKCS5Padding', secKey, secIv);\nc.decryptStr(secCipher)",
                bindings = bindings,
            )
        }
        assertSanitized {
            evaluator.evaluate(
                "java.createSymmetricCrypto('AES/CBC/PKCS5Padding', '$secretKey', '$secretIv').decryptStr('$secretCipher')"
            )
        }
        assertSanitized {
            evaluator.evaluate(
                "var c = java.createSymmetricCrypto('AES/CBC/PKCS5Padding', secKey, secIv); doDec(c, secCipher)",
                bindings = bindings + (RhinoEvaluator.BINDING_JSLIB to { "function doDec(c, s) { return c.decryptStr(s); }" }),
            )
        }
    }

    @Test fun exposesJsoupGlobalAndResponseHelpers() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val js = """
            var doc = Jsoup.parse("<div class='item'>内容文本</div>");
            var txt = doc.select(".item").text();
            txt
        """.trimIndent()
        val result = evaluator.evaluate(js)
        assertEquals("内容文本", result.value)
    }

    @Test fun responseExposesHeaderBytesAndPriorResponse() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val headers = mapOf("Set-Cookie" to "session=123", "Content-Type" to "text/html")
        val fakeResp = com.mina.legadostudio.runtime.StudioJsResponse(200, "http://a.com", "hello", headers, 10, "hello".toByteArray(), null)
        val js = """
            resp.header("set-cookie") + ":" + java.bytesToStr(resp.bytes(), "UTF-8") + ":" + (resp.raw().priorResponse() == null)
        """.trimIndent()
        val result = evaluator.evaluate(js, bindings = mapOf("resp" to fakeResp))
        assertEquals("session=123:hello:true", result.value)
    }

    @Test fun supportsSetContentAndGetStringListAndMemoryPutGet() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val js = """
            java.put('search_key', '天命');
            var k = java.get('search_key');
            java.setContent("<div class='list'><p class='title'>修罗武神</p><p class='title'>天命之子</p></div>");
            var list = java.getStringList(".title@text");
            k + ":" + list.length + ":" + list[0] + ":" + list[1]
        """.trimIndent()
        val result = evaluator.evaluate(js)
        assertEquals("天命:2:修罗武神:天命之子", result.value)
    }
}
