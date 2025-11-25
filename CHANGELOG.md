## 2025-11-25

## 2025-11-25

### 修复

**修复备份取消时已上传文件未被删除的问题**

#### 问题描述

在备份过程中点击取消按钮后,虽然正在上传的文件(如 `user.tar.zst`)的分块上传被正确中止,但已经完成上传的文件(如 `apk.tar.zst`)未被删除,导致存储桶中残留不完整的备份数据。

#### 根本原因

`BackupServiceCloudImpl.onCleanupIncompleteBackup()` 方法使用了基于索引的判断条件 `index >= currentIndex`,导致索引小于 `currentIndex` 的已完成上传文件不在清理范围内。

例如:当 `currentIndex=1` 时,索引为 0 的 `apk.tar.zst` 已经上传成功,但因为 `0 < 1`,所以不会被清理。

#### 解决方案

##### 1. 修改清理判断逻辑

**包备份服务** (`BackupServiceCloudImpl.kt`):
- 将判断条件从 `if (index >= currentIndex)` 改为 `if (pkg.packageEntity.indexInfo.backupTimestamp == timestamp)`
- 使用统一的备份时间戳标识同一备份会话中的所有操作
- 确保清理所有与当前时间戳匹配的包,无论索引位置

##### 2. 添加日志追踪

在 `AbstractProcessingViewModel.CancelAndCleanup` 中添加追踪日志

## 2025-11-24
### 新增功能

**实现 S3 uploadId 持久化机制以自动清理未完成的分块上传**

#### 核心功能

通过数据库持久化 uploadId,实现了完整的 S3 分块上传碎片管理方案,能够处理主动取消、崩溃和网络异常等所有场景下的碎片清理。

#### 技术实现

##### 1. 数据库层
- 创建 `UploadIdEntity` 实体类,包含 `id`、`uploadId`、`bucket`、`key`、`timestamp` 和 `cloudName` 字段
- 创建 `UploadIdDao` 接口,提供 `insert()`、`deleteByUploadId()`、`getAll()` 和 `deleteById()` 方法
- 数据库版本从 8 升级到 9
- 添加 `MIGRATION_8_9` 迁移脚本,创建 `UploadIdEntity` 表
- 在 `DatabaseModule` 中提供 `provideUploadIdDao()` 依赖注入

##### 2. S3 客户端层
- `S3ClientImpl` 构造函数注入 `uploadIdDao` 参数
- 在 `upload()` 方法的三个关键时刻管理 uploadId:
    * **创建时记录**: `createMultipartUpload` 后立即插入 `UploadIdEntity` 到数据库
    * **完成时删除**: `completeMultipartUpload` 成功后删除数据库记录
    * **取消时清理**: catch 块中调用 `abortMultipartUpload` 并删除记录
- 添加 `companion object` 静态方法 `cleanupOrphanedUpload()`,用于启动时清理

##### 3. Repository 层
- `CloudRepository` 构造函数注入 `uploadIdDao` 参数
- `getClient()` 和 `withActivatedClients()` 方法传递 `uploadIdDao` 给 `getCloud()`
- 保持架构分层清晰,避免 feature 模块直接依赖 database 模块

##### 4. 应用启动层
- `DataBackupApplication.onCreate()` 中实现启动时清理逻辑
- 使用 `GlobalScope.launch(Dispatchers.IO)` 在后台线程执行
- 查询所有残留的 `uploadId` 记录
- 逐个调用 `S3ClientImpl.cleanupOrphanedUpload()` 清理 S3 碎片
- 删除数据库记录
- 完善的异常处理和日志记录

##### 5. 接口层改进
- `CloudEntity.getCloud()` 扩展函数添加 `uploadIdDao` 参数
- 只有 S3 协议需要传递该参数,其他协议保持不变
- 所有调用 `getCloud()` 的地方更新为使用 `CloudRepository.getClient()`

#### 工作流程

1. **正常上传流程**:
    - 创建分块上传 → 记录 uploadId 到数据库
    - 上传所有分块 → 完成上传
    - 删除数据库记录

2. **取消上传流程**:
    - 用户点击取消 → 抛出 CancellationException
    - 调用 abortMultipartUpload 清理 S3 碎片
    - 删除数据库记录

3. **崩溃恢复流程**:
    - App 重启 → DataBackupApplication.onCreate()
    - 查询所有残留的 uploadId
    - 逐个调用 S3 接口清理碎片
    - 删除数据库记录

#### 架构优势

- **模块职责清晰**: S3 相关逻辑封装在 `core:network` 模块
- **依赖注入完整**: 通过 Hilt 和 Repository 层传递依赖
- **架构分层清晰**: feature 模块不直接依赖 database 模块
- **代码复用**: 静态方法 `cleanupOrphanedUpload()` 复用 S3 客户端配置逻辑

#### 向后兼容性

- 数据库迁移自动执行,不影响现有数据
- 旧版本备份数据完全兼容
- 其他云存储协议(FTP/SFTP/WebDAV/SMB)不受影响

## 2025-11-22

### 修复
- **S3 恢复进度跟踪**:
    - 修复了 S3 恢复场景中的下载进度显示
    - 下载进度现在实时更新,而不是从 0% 直接跳到 100%
    - 下载过程中显示实时下载速度(例如 "7.5 MB/s | 85%")
    - 完成时显示平均下载速度而非 "0.00 Bytes/s"
    - 添加 HEAD 请求以获取文件大小用于准确的百分比计算
    - 每 300ms 更新一次进度以提供流畅的用户体验
    - 适用于包和媒体恢复操作

## 2025-11-22

**实现备份过程的即时取消响应机制**

### 核心功能

实现了多层次的取消检查机制,使备份操作能够在 1-5 秒内响应用户的取消请求,无论文件大小。

### 技术实现

#### 1. S3 上传取消检查
- 在 `S3ClientImpl.upload()` 中实现三层取消检查:
    - 生产者协程:在读取每个文件分块前检查取消标志
    - 消费者协程:在上传每个分块前检查取消标志
    - 上传完成后:再次检查取消标志
- 检测到取消时抛出 `CancellationException` 并调用 `abortMultipartUpload()` 清理未完成分片
- 使用 Kotlin Coroutines Channel 的取消机制确保所有协程立即停止

#### 2. 压缩过程取消检查
- 在 `PackagesBackupUtil.backupApk()` 和 `backupData()` 中添加 `isCanceled` 参数
- 在 `MediumBackupUtil.backupMedia()` 中添加 `isCanceled` 参数
- 在 `Tar.compress()` 调用后立即检查取消标志
- 检测到取消时返回失败状态,避免继续执行测试和上传操作

#### 3. 备份循环取消检查
- 在 `AbstractBackupService.onProcessing()` 的主循环开始时检查取消标志
- 在 permissions 和 ssaid 备份前检查取消标志
- 检测到取消后立即使用 `for` 退出循环
- 调用 `onCleanupIncompleteBackup(currentIndex)` 清理未完成的备份

#### 4. 接口层改进
- `CloudClient.upload()` 接口添加 `isCanceled: (() -> Boolean)? = null` 参数
- 所有实现类(`FTPClientImpl`、`SFTPClientImpl`、`SMBClientImpl`、`WebDAVClientImpl`、`S3ClientImpl`)更新方法签名
- 使用 `withClient` 包装器确保可空 client 的安全调用
- 只有 `S3ClientImpl` 实现了实际的取消检查逻辑(其他协议保持现有实现)

#### 5. Repository 层传递
- `CloudRepository.upload()` 添加 `isCanceled` 参数并传递给底层 `CloudClient`
- 所有上传配置文件的调用添加空实现 `onUploading = { _, _ -> }`
- 异常处理确保取消操作优雅返回失败状态

#### 6. Service 层集成
- 所有 `BackupService` 实现类在调用 `backup()` 方法时传入 `isCanceled = { isCanceled() }`
- 所有 `onConfigSaved()`、`onItselfSaved()`、`onIconsSaved()`、`onConfigsSaved()` 方法添加取消检查
- `backup()` 方法检查返回结果,失败时立即返回,不继续执行后续操作

#### 7. ViewModel 层支持
- `AbstractProcessingViewModel` 实现 `CancelAndCleanup` intent
- 调用 `destroyService(true)` 立即终止服务
- 清理任务数据库记录并重置任务 ID
- 发送 `NavBack` effect 返回上一页面

### 响应时间

| 场景 | 响应时间 | 说明 |  
|------|---------|------|  
| **小文件上传** (< 10MB) | < 1 秒 | 在下一次进度更新时检测到取消 |  
| **大文件上传** | 1-5 秒 | 取决于单个分块的上传时间(10MB-100MB) |  
| **压缩过程** | 等待压缩完成 | Tar 命令行工具限制,无法中断 |  
| **循环迭代** | 立即 | 在下一次迭代开始时检测到取消 |  

### 工作流程

1. 点击取消按钮
2. ViewModel 调用 `requestCancel()` 设置 `mIsCanceled = true`
3. 下一次循环迭代开始时检查 `isCanceled()` → 如果为 true 则 `for` 退出循环
4. 如果正在压缩,压缩完成后立即检查取消标志并返回失败
5. 如果正在上传,S3ClientImpl 在上传每个分块前检查取消标志并中断上传
6. 调用 `onCleanupIncompleteBackup(currentIndex)` 删除所有索引 >= currentIndex 的备份
7. 已完成的备份(索引 < currentIndex)保持不变

### 技术限制

- 压缩过程使用 Tar 命令行工具,无法在压缩过程中中断,只能在压缩完成后检查取消标志
- 对于特别大的文件(数 GB),压缩可能需要较长时间,但这是 Tar 工具的固有限制
- 上传过程的响应时间取决于网络速度和分块大小,通常在 1-5 秒内

### 向后兼容性

- 所有接口改动使用默认参数 `isCanceled: (() -> Boolean)? = null`,确保向后兼容
- 不传 `isCanceled` 参数的现有调用仍然可以正常工作
- 其他协议(FTP、SFTP、SMB、WebDAV)的实现保持不变,只更新方法签名

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