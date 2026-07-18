# VideoMind 本地启动指南

本文档记录 VideoMind 在本机开发环境中的启动、访问和停止流程。

## 一键启动

在任意 PowerShell 窗口执行：

```powershell
powershell -ExecutionPolicy Bypass -File E:\VideoMind\start.ps1
```

同时启动项目并打开前端页面：

```powershell
powershell -ExecutionPolicy Bypass -File E:\VideoMind\start.ps1 -OpenBrowser
```

该脚本会自动启动 Docker Desktop（如有需要）、Docker 依赖、后端和前端。重复执行不会重复启动已经运行的服务。未配置本地认证密钥时，脚本会在 Git 忽略的 `runtime/local-secrets.env` 中生成稳定的开发密钥；后续启动会继续复用。

一键停止前端、后端和 Docker 依赖：

```powershell
powershell -ExecutionPolicy Bypass -File E:\VideoMind\stop.ps1
```

仅停止前后端，保留 Docker 依赖运行：

```powershell
powershell -ExecutionPolicy Bypass -File E:\VideoMind\stop.ps1 -KeepDocker
```

## 1. 启动 Docker 依赖

在项目根目录执行：

```powershell
cd E:\VideoMind
docker compose up -d mysql minio rocketmq-namesrv rocketmq-broker redis-stack
```

这一步会启动：

- MySQL
- MinIO
- RocketMQ NameServer
- RocketMQ Broker
- Redis Stack

可用下面命令确认容器状态：

```powershell
docker ps
```

## 2. 启动应用

推荐使用一键脚本，它会检查并启动 Docker 依赖、后端和前端：

```powershell
cd E:\VideoMind
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

后端默认端口：

```text
http://localhost:8080
```

可用下面命令验证后端是否启动成功：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/system/capabilities
```

后端日志位置：

```text
E:\VideoMind\runtime\logs\backend-start.out.log
```

## 3. 单独启动前端

仅需单独调试前端时，另开一个终端执行：

```powershell
cd E:\VideoMind\frontend\videomind-web
npm run dev
```

前端默认端口：

```text
http://localhost:5173
```

前端日志位置：

```text
E:\VideoMind\runtime\logs\frontend-start.out.log
```

## 4. 常用访问地址

```text
前端页面：http://localhost:5173
后端 API：http://localhost:8080
MinIO 控制台：http://localhost:9002
RedisInsight：http://localhost:8001
```

默认 MinIO 账号：

```text
Username: minioadmin
Password: minioadmin
```

默认 MySQL 连接：

```text
Host: localhost
Port: 3307
Database: videomind
Username: root
Password: root
```

可通过未跟踪的 `.env` 或环境变量覆盖 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME` 和 `MYSQL_PASSWORD`。

### 4.1 数据库迁移

数据库 DDL 只由 Flyway 管理，迁移目录为：

```text
E:\VideoMind\backend\videomind-server\src\main\resources\db\migration
```

- 全新空数据库从 V1 开始顺序迁移。
- 没有 Flyway 历史表的既有 VideoMind 数据库按版本 7 建立 baseline，再执行 V8 及后续迁移。
- 不再使用 `schema.sql`、Docker 初始化 SQL 或 Java `ApplicationRunner` 修改表结构。
- 新表、字段和索引必须新增版本化迁移，禁止直接修改已经发布并执行过的迁移文件。

## 5. 真实大模型配置

当前一键启动脚本 `start.ps1` 默认切到真实 AI 模式：

```powershell
$env:VIDEOMIND_ASR_MODE = "real"
$env:VIDEOMIND_SUMMARY_MODE = "real"
$env:VIDEOMIND_EMBEDDING_MODE = "real"
$env:VIDEOMIND_CHAT_MODE = "real"
```

真实模式需要本机配置 `SILICONFLOW_API_KEY`，或分别配置：

```powershell
CHAT_API_KEY
EMBEDDING_API_KEY
SUMMARY_API_KEY
ASR_API_KEY
```

如果只是想本地跑通流程，不调用真实 API，可在未跟踪的 `.env` 中把对应模式设置为 `mock` 后重启后端。

## 6. 停止项目

### 6.1 常规停止

如果前端和后端是在终端里启动的，进入对应终端按：

```text
Ctrl + C
```

分别停止：

```powershell
mvn spring-boot:run
npm run dev
```

然后停止 Docker 依赖：

```powershell
cd E:\VideoMind
docker compose stop
```

### 6.2 按端口停止前后端

如果找不到前端或后端是在哪个终端启动的，可以按端口停止。

前端默认端口是 `5173`，后端默认端口是 `8080`：

```powershell
$ports = 8080,5173
$pids = Get-NetTCPConnection -LocalPort $ports -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($pidValue in $pids) {
    if ($pidValue -and $pidValue -ne $PID) {
        Stop-Process -Id $pidValue -Force
    }
}
```

然后停止 Docker 依赖：

```powershell
cd E:\VideoMind
docker compose stop
```

如果想同时删除容器网络等 Compose 创建的资源，但保留数据卷，执行：

```powershell
docker compose down
```

### 6.3 确认是否停干净

查看 Docker 容器：

```powershell
docker ps
```

查看前端和后端端口：

```powershell
Get-NetTCPConnection -LocalPort 8080,5173 -ErrorAction SilentlyContinue
```

如果 `docker ps` 里没有 VideoMind 相关容器，且 `8080`、`5173` 没有 `Listen` 状态，就说明项目已经停好了。

## 7. 推荐启动顺序

每次从零启动时执行一键脚本即可：

```powershell
cd E:\VideoMind
powershell -ExecutionPolicy Bypass -File .\start.ps1
```
