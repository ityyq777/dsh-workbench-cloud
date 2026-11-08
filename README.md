# dsh-workbench-cloud

个人工作台云端服务，Java 21 + Spring Boot + PostgreSQL + S3/MinIO。

这是 [dsh-workbench](https://github.com/ityyq777/dsh-workbench-source) 的**独立云端仓库**。
本地宿主（Electron/浏览器侧）仍通过 protocol v2 与它同步：

- 账号 / 设备 / refresh token 轮换
- protocol v2 同步：open / push / pull / snapshot / full / close
- 文件字节面：S3（MinIO 兼容）或本地目录，按 sha256 内容寻址

## 快速启动

要求：Java 21、Maven 3.9+、Docker（仅用于 PostgreSQL）。

```bash
cp .env.example .env
chmod 600 .env
# 修改 .env 中的 WB_DB_PASSWORD、WB_S3_ACCESS_KEY_ID、WB_S3_SECRET_ACCESS_KEY

docker compose up -d postgres

set -a; . ./.env; set +a
mvn -DskipTests package
java -jar target/workbench-cloud.jar
```

服务默认监听 `0.0.0.0:8791`。健康检查：

```bash
curl http://127.0.0.1:8791/actuator/health
```

MinIO 的 S3 API 在宿主机上通常是 `http://127.0.0.1:9690`；如果服务以后放进容器，
把 `WB_S3_ENDPOINT` 改成容器内可达的地址（例如 `http://host.docker.internal:9690`）。

## 配置

所有配置都可以用环境变量覆盖，见 [.env.example](.env.example)。关键项：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `WB_DB_URL` | `jdbc:postgresql://127.0.0.1:55432/workbench` | PostgreSQL JDBC URL |
| `WB_BLOB_BACKEND` | `s3` | `s3` 或 `local` |
| `WB_S3_ENDPOINT` | 空 | MinIO/S3 端点，例如 `http://127.0.0.1:9690` |
| `WB_S3_BUCKET` | `workbench` | 桶名，启动时自动创建 |
| `WB_S3_FORCE_PATH_STYLE` | `true` | MinIO 需要 true |
| `WB_FILES_MAX_BYTES` | 200MB | 单文件上限 |
| `WB_FILES_QUOTA_BYTES` | 20GB | 每用户 blob 总量 |

## 部署（开发阶段）

服务器上 `git clone` 后直接启动，不打应用镜像：

```bash
git clone https://github.com/ityyq777/dsh-workbench-cloud.git
cd dsh-workbench-cloud
cp .env.example .env
chmod 600 .env
# 填 PostgreSQL 密码与 MinIO AK/SK
docker compose up -d postgres
mvn -DskipTests package
nohup java -jar target/workbench-cloud.jar > workbench-cloud.log 2>&1 &
```

生产化时再加 systemd、TLS 反向代理、数据库备份；当前阶段先保证“clone 即可跑”。

## API

认证：

```
POST /v1/auth/signup   {email,password,device_id,device_name}
POST /v1/auth/login    {email,password,device_id,device_name}
POST /v1/auth/refresh  {refresh_token}
GET  /v1/me
DELETE /v1/devices/:id
```

同步：

```
POST /v1/sync/open      {protocol:2,device_id}
POST /v1/sync/push      {session_id,client_epoch,changes:[...]}
POST /v1/sync/pull      {session_id,cursors,limit}
POST /v1/sync/snapshot  {session_id,table}
POST /v1/sync/full      {session_id,table,base_version,rows}
POST /v1/sync/close     {session_id,success,cursors}
```

文件：

```
POST /v1/files/check          {hashes:[...]}
PUT  /v1/files/blobs/:sha256  裸字节流，Content-Length 必填
GET  /v1/files/blobs/:sha256  默认 302 到预签名 URL，?proxy=1 走服务端
HEAD /v1/files/blobs/:sha256
GET  /v1/files/quota
```

文件下载要求该 sha 至少有一条未删除的 `blob_refs`；`blob_refs` 由同步 push 接受
`file` 行后维护。上传可以先于元数据，孤儿对象由 GC 在宽限期后清理。

## 数据库

PostgreSQL，Flyway 在启动时自动建表。开发库在 `docker-compose.yml` 中，
数据落在 `./data/postgres`。不要复用服务器上其他项目的 PostgreSQL 容器。

## 测试

```bash
mvn test
```
