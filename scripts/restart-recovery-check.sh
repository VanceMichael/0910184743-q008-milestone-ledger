#!/usr/bin/env bash
# 重启恢复验证：应用与数据库容器重启后，审计游标不回退、服务恢复健康。
# 用法: scripts/restart-recovery-check.sh
set -euo pipefail
cd "$(dirname "$0")/.."

cursor() {
  curl -fsS "http://localhost:8080/api/meta/audit-cursor" | sed -E 's/.*"maxSeq":([0-9]+).*/\1/'
}

wait_healthy() {
  for _ in $(seq 1 60); do
    if curl -fsS http://localhost:8080/health >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "服务未在预期时间内恢复健康" >&2
  exit 1
}

docker compose up -d --build --wait
before=$(cursor)
echo "重启前审计游标: $before"

docker compose restart app
wait_healthy
after_app=$(cursor)
[ "$after_app" -ge "$before" ] || { echo "应用重启后审计游标回退: $before -> $after_app" >&2; exit 1; }
echo "应用重启后审计游标: $after_app (>= $before) ✓"

docker compose restart postgres
wait_healthy
after_db=$(cursor)
[ "$after_db" -ge "$before" ] || { echo "数据库重启后审计游标回退: $before -> $after_db" >&2; exit 1; }
echo "数据库重启后审计游标: $after_db (>= $before) ✓"

echo "重启恢复验证通过"
