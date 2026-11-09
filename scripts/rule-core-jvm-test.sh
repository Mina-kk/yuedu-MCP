#!/usr/bin/env bash
# 轻量 JVM 编译/测试 runner：只用已下载的 Gradle 缓存 jar，不启动 Gradle，
# 供规则解析核（ReaderRuleSession + io/legado/app/model/analyzeRule）单独验证。
# 用法: scripts/rule-core-jvm-test.sh [--compile-only]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
OUT="${RULE_CORE_OUT:-$ROOT/build/rule-core-jvm}"
COMPILE_ONLY="${1:-}"

jar() { # jar <group> <artifact> [version]
  local g="$1" a="$2" v="${3:-}"
  local p
  if [ -n "$v" ]; then p="$CACHE/$g/$a/$v"; else p="$CACHE/$g/$a"; fi
  find "$p" -name "$a-*.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | tail -1
}

CP=""
add() {
  local j
  j="$(jar "$1" "$2" "${3:-}")"
  if [ -z "$j" ]; then echo "MISSING jar: $1:$2:${3:-*}" >&2; exit 2; fi
  CP="$CP:$j"
}
add org.jetbrains.kotlin kotlin-stdlib 2.3.20
add org.jsoup jsoup 1.21.2
add cn.wanghaomiao JsoupXpath 2.5.3
add com.jayway.jsonpath json-path 3.0.0
add com.google.code.gson gson 2.13.2
add junit junit 4.13.2
add org.hamcrest hamcrest-core
for extra in net.minidev json-smart org.slf4j slf4j-api org.apache.commons commons-lang3 org.json json; do
  g="${extra% *}"; a="${extra#* }"
  j="$(jar "$g" "$a" || true)"
  [ -n "$j" ] && CP="$CP:$j"
done
# 传递依赖：json-path 需要 json-smart/accessors-smart，JsoupXpath 需要 antlr4-runtime
for a in json-smart accessors-smart antlr4-runtime asm; do
  j="$(find "$CACHE" -name "$a-*.jar" ! -name '*-sources.jar' 2>/dev/null | sort | tail -1)"
  [ -n "$j" ] && CP="$CP:$j"
done
CP="${CP#:}"

KOTLINC_JAR="$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name 'kotlin-compiler-embeddable-*.jar' | sort | tail -1)"
[ -n "$KOTLINC_JAR" ] || { echo "MISSING kotlin-compiler-embeddable" >&2; exit 2; }
KOTLINC_CP="$KOTLINC_JAR"
for a in kotlin-stdlib kotlin-script-runtime kotlin-reflect kotlin-daemon-embeddable; do
  j="$(jar org.jetbrains.kotlin "$a" || true)"; [ -n "$j" ] && KOTLINC_CP="$KOTLINC_CP:$j"
done
for a in kotlinx-coroutines-core-jvm kotlinx-coroutines-core; do
  j="$(find "$CACHE/org.jetbrains.kotlinx" -name "$a-*.jar" ! -name '*-sources.jar' 2>/dev/null | sort | tail -1)"
  [ -n "$j" ] && KOTLINC_CP="$KOTLINC_CP:$j"
done
for a in trove4j annotations intellij-core; do
  j="$(find "$CACHE" -name "$a-*.jar" ! -name '*-sources.jar' 2>/dev/null | sort | tail -1)"
  [ -n "$j" ] && KOTLINC_CP="$KOTLINC_CP:$j"
done

MAIN_SRC=(
  "$ROOT/app/src/main/java/io/legado/app/model/analyzeRule"
  "$ROOT/app/src/main/java/com/mina/legadostudio/runtime/ReaderRulePart.kt"
  "$ROOT/app/src/main/java/com/mina/legadostudio/runtime/ReaderRuleSession.kt"
)
TEST_SRC=("$ROOT/app/src/test/java/com/mina/legadostudio/runtime/ReaderRuleSessionTest.kt")
# Android SDK 类不在纯 JVM classpath 上：只用 build/ 下的最小签名桩，绝不进 src/ 或 APK。
STUB_SRC=("$OUT/stubs")

rm -rf "$OUT/main" "$OUT/test"
mkdir -p "$OUT/main" "$OUT/test" "$OUT/stubs"
cat > "$OUT/stubs/PlatformStubs.kt" <<'KOTLIN'
// JVM-only compile stubs for the offline rule-core runner. NOT part of the APK build.
package androidx.annotation

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class Keep
KOTLIN
cat > "$OUT/stubs/TextUtilsStub.kt" <<'KOTLIN'
// JVM-only compile stub: android.text.TextUtils.join used by AnalyzeByXPath. NOT part of the APK.
package android.text

object TextUtils {
    @JvmStatic
    fun isEmpty(str: CharSequence?): Boolean = str == null || str.isEmpty()

    @JvmStatic
    fun join(delimiter: CharSequence, tokens: Iterable<*>): String {
        val sb = StringBuilder()
        var first = true
        for (token in tokens) {
            if (!first) sb.append(delimiter)
            sb.append(token.toString())
            first = false
        }
        return sb.toString()
    }
}
KOTLIN

kotlinc() {
  java -Xmx900m -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"
}

echo "== compile main =="
kotlinc -no-stdlib -no-reflect -jvm-target 17 -nowarn -cp "$CP" -d "$OUT/main" "${STUB_SRC[@]}" "${MAIN_SRC[@]}"
echo "== compile test =="
kotlinc -no-stdlib -no-reflect -jvm-target 17 -nowarn -cp "$CP:$OUT/main" -d "$OUT/test" "${TEST_SRC[@]}"

if [ "$COMPILE_ONLY" = "--compile-only" ]; then
  echo "compile OK"
  exit 0
fi

echo "== run tests =="
java -cp "$CP:$OUT/main:$OUT/test" org.junit.runner.JUnitCore com.mina.legadostudio.runtime.ReaderRuleSessionTest
