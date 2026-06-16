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

该脚本会自动启动 Docker Desktop（如有需要）、Docker 依赖、后端和前端。重复执行不会重复启动已经运行的服务。

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
cd C:\Users\Administrator\Documents\VideoMind
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

## 2. 启动后端

项目已经准备了后端启动脚本：

```powershell
cd C:\Users\Administrator\Documents\VideoMind
powershell -ExecutionPolicy Bypass -File .\runtime\start-backend.ps1
```

后端默认端口：

```text
http://localhost:8080
```

可用下面命令验证后端是否启动成功：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/videos/list
```

后端日志位置：

```text
C:\Users\Administrator\Documents\VideoMind\runtime\logs\backend-20260531.log
```

## 3. 启动前端

另开一个终端，执行：

```powershell
cd C:\Users\Administrator\Documents\VideoMind\frontend\videomind-web
npm run dev
```

前端默认端口：

```text
http://localhost:5173
```

前端日志位置：

```text
C:\Users\Administrator\Documents\VideoMind\runtime\logs\frontend.log
```

## 4. 常用访问地址

```text
前端页面：http://localhost:5173
后端 API：http://localhost:8080
MinIO 控制台：http://localhost:9001
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

## 5. 真实大模型配置

当前后端启动脚本 `runtime/start-backend.ps1` 已切到真实 AI 模式：

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

如果只是想本地跑通流程，不调用真实 API，可以把 `runtime/start-backend.ps1` 中对应模式改成 `mock` 后重启后端。

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
cd C:\Users\Administrator\Documents\VideoMind
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
cd C:\Users\Administrator\Documents\VideoMind
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

每次从零启动时，按下面顺序执行：

```powershell
cd C:\Users\Administrator\Documents\VideoMind
docker compose up -d mysql minio rocketmq-namesrv rocketmq-broker redis-stack
```

```powershell
cd C:\Users\Administrator\Documents\VideoMind
powershell -ExecutionPolicy Bypass -File .\runtime\start-backend.ps1
```

```powershell
cd C:\Users\Administrator\Documents\VideoMind\frontend\videomind-web
npm run dev
```
