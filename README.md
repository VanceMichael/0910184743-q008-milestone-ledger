# 科技成果转化里程碑账本

Java 21 项目记录脱敏证据、项目版本和里程碑决定，PostgreSQL 16 由 Compose 提供。迁移脚本和样例位于 `src/main/resources`。

```bash
mvn test
docker compose config
docker compose build
docker compose up
```

服务探活地址为 `GET /health`，数据库口令仅用于本地 Compose，不写入其他环境。
