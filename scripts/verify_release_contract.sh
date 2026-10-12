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
)
for cls in "${classes[@]}"; do
  line=$(grep -F "$cls -> " "$MAP" | head -1 || true)
  [[ -n "$line" ]] || { echo "missing contract class in mapping: $cls" >&2; exit 1; }
  target=${line#* -> }; target=${target%:}
  [[ "$target" == "$cls" ]] || { echo "contract class obfuscated: $cls -> $target" >&2; exit 1; }
done
echo RELEASE_JSON_CONTRACT_OK
