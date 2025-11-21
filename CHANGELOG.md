## 2025-11-21

**优化 S3 上传性能并新增网络类型选择**

### 核心变更

#### 1. 优化上传备份逻辑
- 实现基于 Kotlin Coroutines Channel 的并发上传机制
- 采用生产者-消费者模式,提高上传效率
- 优化内存使用策略,降低 OOM 风险

#### 2. 优化分块上传从串行到并行
- **公网环境 (PUBLIC)**:
   - 并发数: 3 个
   - 预期性能: 从 3-5MB/s 提升到 9-15MB/s (约 4 倍提升)
   - 适用场景: AWS S3、阿里云 OSS、腾讯云 COS 等公有云服务

- **内网环境 (PRIVATE)**:
   - 并发数: 5 个
   - 预期性能: 从 3-5MB/s 提升到 10-25MB/s (约 5 倍提升)
   - 适用场景: MinIO、Ceph 等自建 S3 兼容存储

#### 3. 新增 S3 公网、内网选项
- 添加 `S3NetworkType` 枚举类型 (`PUBLIC` / `PRIVATE`)
- 在 S3 配置页面新增网络类型选择器 UI
- 使用 `SingleChoiceSegmentedButtonRow` 组件,与协议选择器风格统一
- 根据用户选择的网络类型自动调整并发参数
- 更新数据模型: `S3Extra` 添加 `networkType` 字段
- 向后兼容: 旧数据自动使用 `PUBLIC` 默认值

## 2025-11-20
feat: 实现基于时间戳的备份机制以支持多版本备份
## 核心变更

### 数据模型层
- 为 PackageEntity 和 MediaEntity 添加 backupTimestamp 字段
- 为 PackageExtraInfo 和 MediaExtraInfo 添加 isProtected 字段
- 更新 archivesRelativeDir 属性以使用时间戳格式(包名/user_用户ID@时间戳)
- 更新 asExternalModel() 方法以映射新字段到 UI 模型

### 数据库层
- 创建 MIGRATION_7_8 迁移脚本,添加 indexInfo_backupTimestamp 和 extraInfo_isProtected 列
- 更新 PackageDao 和 MediaDao 查询方法,将 backupTimestamp 作为唯一性判断条件
- 添加 queryByPreserveId 方法以保持向后兼容性
- 将数据库版本从 7 升级到 8

### 数据仓库层
- 实现 parseTimestampAndUserId 方法从路径中解析时间戳
- 更新 loadLocalApps/loadCloudApps 和 loadLocalFiles/loadCloudFiles 方法以支持时间戳
- 简化受保护功能:从文件复制改为标记(使用 isProtected 字段)
- 修正所有数据库查询调用以包含 backupTimestamp 参数

### 备份服务层
- 在 onInitializing() 中生成统一的备份时间戳
- 实现 onCleanupFailedBackup() 方法以清理失败的备份
- 更新备份逻辑以使用带时间戳的目录结构
- 在备份失败时自动调用清理逻辑

### UI 层
- 更新 ListItems.kt 以显示 3 行布局(应用名称/包名/备份时间)
- 仅在恢复模式(OpType.RESTORE)下显示时间戳
- 使用 isProtected 字段而非 preserveId 显示受保护图标
- 更新 AppDetails.kt 以正确显示受保护状态和备份时间

## 功能特性  （***重大功能更新***）

- ✅ 支持同一应用/文件的多个时间点备份共存
- ✅ 在恢复列表中显示所有历史备份
- ✅ 备份失败时自动清理部分上传的文件
- ✅ 向后兼容旧的备份数据(无时间戳)
- ✅ 受保护功能简化为标记而非文件复制
- 
## 2025-11-20

**实现 S3 分块复制和受保护操作进度显示**

1. 修改简单 copy 为分块 copy
    - 在 S3ClientImpl.renameTo() 中实现指数级增长的分块复制策略
    - 使用 listObjectsV2 列出目录下所有对象
    - 对每个文件计算最优分块大小(最多 10000 个分块,每块最大 5GB)
    - 使用 uploadPartCopy 执行分块复制,支持大文件传输
    - 添加自动重试机制(最多 3 次)
    - 使用批量删除 API 清理源对象

2. 修改受保护操作展示分块进度
    - 在 DetailsViewModel 中添加 isProtecting 和 protectProgress 状态
    - 在 AppsRepo 和 FilesRepo 中添加 onProgress 回调参数
    - 实现完整的进度回调链路:S3ClientImpl → Repository → ViewModel → UI
    - 在 AppDetails 和 FileDetails 中实现进度文本显示(如 "1/50")
    - 使用 ActionSegmentedButton 自定义按钮内容,支持动态切换显示
    - 保持按钮布局和背景颜色与其他操作按钮一致

## 2025-11-17

**修复云端重载数据一致性**

* 确保云端重载操作后,本地数据库记录与远端 S3 存储完全同步

提交: [fc0ee78](https://github.com/543069760/Android-DataBackup-S3/commit/fc0ee78a0fa25f12e258f58cf16f9bea1962b71f)

---

## 2025-11-15

**修复 S3 受保护操作的 renameTo 逻辑并添加 loading 指示器**

* 修复 `S3ClientImpl.renameTo()` 方法,从只复制单个对象改为列出并复制目录下的所有对象
* 使用 `listObjectsV2` API 获取源目录前缀下的所有对象
* 使用批量删除 API (`deleteObjects`) 删除所有源对象
* 在 `AppsRepo.protectCloudApp()` 和 `FilesRepo.protectCloudFile()` 中添加数据库更新调用
* 确保 `preserveId` 被正确保存到数据库,使盾牌图标能够显示
* 添加 `isProtecting` 状态跟踪保护操作进度
* 在操作进行时显示 `CircularProgressIndicator` 替代盾牌图标
* 解决了目录重命名失败、盾牌图标不显示、文件大小无法获取等问题

提交: [b89f03a](https://github.com/543069760/Android-DataBackup-S3/commit/b89f03a3d25c7efe4b43ed4f3798328a678be78e)

---

## 2025-11-15

**修复 S3 文件删除功能并统一协议选择 UI 风格**

* 修复 S3 文件删除问题,移除 `exists` 检查,直接调用 `deleteRecursively`
* 为数据库操作添加 try-catch 包裹,防止崩溃
* 添加详细的删除过程日志追踪
* 将协议下拉菜单替换为分段按钮行 (`SingleChoiceSegmentedButtonRow`)
* 与 FTP 认证模式 UI 保持一致
* 修复了 S3 文件备份确认删除后不会被删除的问题

提交: [c5b1d28](https://github.com/543069760/Android-DataBackup-S3/commit/c5b1d285d25d244eb8645d9bbf9ec05680be3f39) / [8d5d20f](https://github.com/543069760/Android-DataBackup-S3/commit/8d5d20fca434f25c67ce6e189eab3fe31d2f538e)

---

## 2025-11-15

**添加 HTTP/HTTPS 协议选择和动态分块大小计算**

* 添加 `S3Protocol` 枚举 (HTTP/HTTPS),默认为 HTTPS
* 实现基于文件大小的动态分块大小计算(最多 10000 个分块,每块最小 5MB)
* 在 S3 设置页面添加协议选择 UI
* 支持本地 S3 兼容存储(如 MinIO)使用 HTTP 协议,避免无 SSL 证书时的访问错误
* 支持大文件上传,不会超过 S3 的 10000 个分块限制

提交: [7f3f1ab](https://github.com/543069760/Android-DataBackup-S3/commit/7f3f1ab89530939f8a43a31e5b035073c2055c6a)