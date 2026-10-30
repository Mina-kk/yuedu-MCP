#!/usr/bin/env bash
set -euo pipefail
MAP="${1:-app/build/outputs/mapping/release/mapping.txt}"
[[ -f "$MAP" ]] || { echo "mapping missing: $MAP" >&2; exit 1; }
# MCP + Runtime JSON 契约类保持不混淆。
classes=(
  com.mina.legadostudio.skills.SkillRepository\$SkillInfo
  com.mina.legadostudio.data.db.ProjectEntity
  com.mina.legadostudio.data.db.VerificationSessionEntity
  com.mina.legadostudio.network.HttpFetcher\$FetchResult
  com.mina.legadostudio.runtime.LegadoRuntime\$DebugReport
  com.mina.legadostudio.runtime.RhinoEvaluator\$Result
  com.mina.legadostudio.domain.BookSourceValidator
)
for cls in "${classes[@]}"; do
  line=$(grep -F "$cls -> " "$MAP" | head -1 || true)
  [[ -n "$line" ]] || { echo "missing contract class in mapping: $cls" >&2; exit 1; }
  target=${line#* -> }; target=${target%:}
  [[ "$target" == "$cls" ]] || { echo "contract class obfuscated: $cls -> $target" >&2; exit 1; }
done

# ---------- 字段级 JSON 契约 ----------
# 类名保住还不够：R8 会把字段改名（如 StageEvidence.category→e、facts→f），
# Gson 序列化键随之变化。逐字段核对「源字段 → 混淆名」映射。
#
# 每行格式：类名<TAB>模式<TAB>源字段[,源字段...]<TAB>期望JSON键[,...]
# 模式 keep       → 字段名必须在 mapping 中原样保留（类靠 proguard keep，
#                   期望键与源字段同名）；
# 模式 serialized → 源字段只要还在类里即可（mapping 中允许映射成任意混淆名），
#                   JSON 键由 @SerializedName 注解钉死，与字段混淆名无关——
#                   这就是「SerializedName 策略」，避免把注解保护误报成优化映射。
# 注意：R8 只给「被改名/被删」的成员写映射行——类名保住（keep 生效）时字段缺席
# 即「R8 没碰」= 原名保留，放行；类已混淆时字段缺席按字段被删处理，统一记违约。
FIELD_CONTRACTS=(
  # StageEvidence 有 @Keep + @SerializedName（release 曾出 category→e/facts→f 事故）：
  # @Keep 保证类不被混淆/删除，@SerializedName+keepclassmembers 让字段保留原名，
  # 所以 mapping 中字段应原样保留，或类 kept 时缺席（R8 不给未改名成员写行）。
  'com.mina.legadostudio.domain.SourceCheckEvidence$StageEvidence	keep	stage,ran,ok,reason,category,facts,sandboxReason	stage,ran,ok,reason,category,facts,sandboxReason'
  'com.mina.legadostudio.runtime.LegadoRuntime$DebugReport	keep	type,entry,lines,data	type,entry,lines,data'
  'com.mina.legadostudio.domain.BookSourceValidator$Issue	keep	path,message	path,message'
  'com.mina.legadostudio.domain.BookSourceValidator$Report	keep	issues,warnings	issues,warnings'
)

# 在 mapping 中截取「类名 -> 目标:」到下一个顶层类头之间的成员区段，输出到 stdout。
class_block() {
  # 类名含 $/. 正则元字符，用 index() 做字面量前缀匹配，不走正则。
  awk -v cls="$1" '
    index($0, cls " -> ") == 1 { in_block = 1; print; next }
    in_block && /^[^[:space:]#]/ { in_block = 0 }
    in_block { print }
  ' "$MAP"
}

for contract in "${FIELD_CONTRACTS[@]}"; do
  IFS=$'\t' read -r cls mode srcs keys <<<"$contract"
  header=$(grep -F "$cls -> " "$MAP" | head -1 || true)
  [[ -n "$header" ]] || { echo "missing field-contract class in mapping: $cls" >&2; exit 1; }
  target=${header#* -> }; target=${target%:}
  # R8 只给「被改名/移除」的成员写映射行；未被处理的成员整段不出现在 mapping 里。
  # 类名未混淆（keep 生效）时，字段在 mapping 里缺席 = 原样保留，不当作违约。
  class_kept=false
  [[ "$target" == "$cls" ]] && class_kept=true
  block=$(class_block "$cls")
  [[ -n "$block" ]] || { echo "no members found under mapping entry: $cls" >&2; exit 1; }
  IFS=',' read -ra src_arr <<<"$srcs"
  IFS=',' read -ra key_arr <<<"$keys"
  [[ ${#src_arr[@]} -eq ${#key_arr[@]} ]] || { echo "malformed FIELD_CONTRACTS row for $cls" >&2; exit 1; }
  for i in "${!src_arr[@]}"; do
    src=${src_arr[$i]}; key=${key_arr[$i]}
    # mapping 成员行形态：可选行号前缀 + 返回类型 + 字段名 + " -> " + 混淆名。
    # 匹配 " java.lang.String stage -> a" / " 7:9:boolean ran -> b" 两类。
    mapped=$(printf '%s\n' "$block" \
      | sed -nE "s/^[[:space:]]+([0-9]+:[0-9]+:)?[^ ]+ ${src} -> ([^ :]+).*/\2/p" \
      | head -1)
    case "$mode" in
      keep)
        # 纯 keep 策略：字段名必须原样保留，否则 Gson 键随混淆名漂移。
        # mapping 里缺席：类已混淆时是违约；类名本身保住时是 R8 没碰该字段 = 保留原名，放行。
        if [[ -z "$mapped" && "$class_kept" == true ]]; then
          :
        elif [[ "$mapped" != "$src" ]]; then
          echo "JSON contract field lost: $cls.$src (mapped to '${mapped:-<absent>}', expected kept name '$key')" >&2; exit 1
        fi
        ;;
      serialized)
        # SerializedName 策略：源字段还在即可——Gson 键来自注解值 $key，
        # 与混淆后字段名无关。
        # mapping 里缺席：类已混淆时是字段被删的违约；类名保住时是 R8 没碰该字段 = 原名保留，放行。
        if [[ -z "$mapped" && "$class_kept" == false ]]; then
          echo "JSON contract field dropped: $cls.$src (not in mapping; JSON key '$key' via SerializedName would be absent)" >&2; exit 1
        fi
        ;;
      *) echo "unknown contract mode '$mode' for $cls" >&2; exit 1 ;;
    esac
  done
done

echo RELEASE_JSON_CONTRACT_OK
