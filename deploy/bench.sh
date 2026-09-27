#!/usr/bin/env bash
# Benchmark an OpenAI-compatible server (vLLM or Ollama) with N parallel requests.
# Usage: ./bench.sh <base_url> <model> <parallel_requests>
#   vLLM:   ./bench.sh http://localhost:8000 local-model 20
#   Ollama: ./bench.sh http://localhost:11434 qwen2.5:1.5b 20

URL=${1:-http://localhost:8000}
MODEL=${2:-local-model}
N=${3:-10}

PROMPT="Summarize this support ticket in one sentence: My payment failed twice this morning and I cannot access my account. I need this fixed today."
BODY=$(printf '{"model":"%s","messages":[{"role":"user","content":"%s"}],"max_tokens":64,"temperature":0}' "$MODEL" "$PROMPT")

TMP=$(mktemp -d)
echo "Sending $N parallel requests to $URL (model: $MODEL)..."

START=$(date +%s.%N)
for i in $(seq 1 "$N"); do
  curl -s -o /dev/null -w "%{http_code} %{time_total}\n" \
    -H "Content-Type: application/json" -d "$BODY" \
    "$URL/v1/chat/completions" > "$TMP/$i" &
done
wait
END=$(date +%s.%N)

cat "$TMP"/* | awk -v n="$N" -v start="$START" -v end="$END" '
  { if ($1 == 200) { ok++; sum += $2; if ($2 > max) max = $2 } else { failed++ } }
  END {
    total = end - start
    printf "\nSuccessful:        %d / %d\n", ok, n
    if (failed) printf "Failed:            %d\n", failed
    printf "Total wall time:   %.2f s\n", total
    if (ok) {
      printf "Avg per request:   %.2f s\n", sum / ok
      printf "Slowest request:   %.2f s\n", max
      printf "Throughput:        %.2f requests/s\n", ok / total
    }
  }'

rm -rf "$TMP"
