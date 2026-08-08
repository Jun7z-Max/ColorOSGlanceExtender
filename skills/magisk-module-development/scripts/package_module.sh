#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "用法: $0 <模块根目录> [输出.zip]" >&2
  exit 2
fi

root="$(cd "$1" && pwd)"
[[ -f "$root/module.prop" ]] || { echo "错误: $root/module.prop 不存在" >&2; exit 1; }

validator="$(cd "$(dirname "$0")" && pwd)/validate_module.py"
python3 "$validator" "$root"

if [[ $# -eq 2 ]]; then
  output="$2"
else
  module_id="$(awk -F= '$1=="id" {print substr($0, index($0, "=") + 1); exit}' "$root/module.prop")"
  version_code="$(awk -F= '$1=="versionCode" {print substr($0, index($0, "=") + 1); exit}' "$root/module.prop")"
  output="$(dirname "$root")/${module_id}-${version_code}.zip"
fi

case "$output" in
  /*) ;;
  *) output="$(pwd)/$output" ;;
esac
mkdir -p "$(dirname "$output")"
rm -f "$output"

(
  cd "$root"
  /usr/bin/zip -X -r "$output" . \
    -x '.DS_Store' '*/.DS_Store' \
       '.git/*' '*/.git/*' \
       '.github/*' '*/.github/*' \
       '.idea/*' '*/.idea/*' \
       '.gradle/*' '*/.gradle/*' \
       'build/*' '*/build/*' \
       '*.zip'
)

if ! /usr/bin/unzip -Z1 "$output" | grep -qx 'module.prop'; then
  echo '错误: ZIP 根层级没有 module.prop' >&2
  exit 1
fi

echo "已生成: $output"
/usr/bin/unzip -l "$output"
