#!/usr/bin/env bash
# Prints the sample CSV files as aligned tables, then the relationships they describe in plain English,
# so the input data can be walked through before the demo.
# Usage: scripts/show-data.sh [samples-dir]      (also available as `make data`)

set -euo pipefail
DIR="${1:-samples}"

# Bold/dim only when printing to a terminal (no stray escape codes when piped or redirected).
if [ -t 1 ]; then
  bold=$(tput bold 2>/dev/null || true); dim=$(tput dim 2>/dev/null || true); reset=$(tput sgr0 2>/dev/null || true)
else
  bold=""; dim=""; reset=""
fi

# Prints one CSV file as an aligned table under a heading.
show() {
  local file="$1" title="$2"
  local rows=$(($(grep -cv '^[[:space:]]*$' "$file") - 1))
  echo "${bold}${title}${reset}  ${dim}${file}, ${rows} rows${reset}"
  column -t -s, "$file" | sed 's/^/    /'
  echo
}

# Vertex files have an "id" first column; edge files start with "from".
vertex_files=()
edge_files=()
for f in "$DIR"/*.csv; do
  case "$(head -1 "$f")" in
    id,*)   vertex_files+=("$f") ;;
    from,*) edge_files+=("$f") ;;
  esac
done

echo
echo "${bold}=== VERTICES (nodes) ===${reset}  ${dim}required columns: id,label; other columns become properties; name:type sets a type${reset}"
echo
for f in "${vertex_files[@]}"; do show "$f" "$(basename "$f" .csv)"; done

echo "${bold}=== EDGES (relationships) ===${reset}  ${dim}required columns: from,to,label (from/to are vertex ids)${reset}"
echo
for f in "${edge_files[@]}"; do show "$f" "$(basename "$f" .csv)"; done

# Relationships in words: map vertex id -> "name (label)", then print each edge using those names.
echo "${bold}=== WHAT THE DATA SAYS ===${reset}"
echo
awk -F, '
  FNR == 1 {                                   # header row: remember column positions for this file
    delete col
    for (i = 1; i <= NF; i++) { split($i, h, ":"); col[h[1]] = i }
    edge = ("from" in col)
    next
  }
  /^[[:space:]]*$/ { next }
  !edge {                                      # vertex row
    n = ("name" in col) ? $col["name"] : $col["id"]
    name[$col["id"]] = n " (" $col["label"] ")"
    next
  }
  {                                            # edge row
    w = ("weight" in col) ? "  weight " $col["weight"] : ""
    printf "    %-18s %-14s %-20s%s\n", name[$col["from"]], "--" $col["label"] "-->", name[$col["to"]], w
  }
' "${vertex_files[@]}" "${edge_files[@]}"

nv=0; for f in "${vertex_files[@]}"; do nv=$((nv + $(grep -cv '^[[:space:]]*$' "$f") - 1)); done
ne=0; for f in "${edge_files[@]}"; do ne=$((ne + $(grep -cv '^[[:space:]]*$' "$f") - 1)); done
echo
echo "    ${bold}Total: ${nv} vertices, ${ne} edges${reset}   ${dim}load it with: make load${reset}"
echo
