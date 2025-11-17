2025-11-17 Section copied!
修复云端重载数据一致性

确保云端重载操作后,本地数据库记录与远端 S3 存储完全同步
提交: fc0ee78

2025-11-15 Section copied!
修复 S3 受保护操作的 renameTo 逻辑并添加 loading 指示器

修复 S3ClientImpl.renameTo() 方法,从只复制单个对象改为列出并复制目录下的所有对象
使用 listObjectsV2 API 获取源目录前缀下的所有对象
使用批量删除 API (deleteObjects) 删除所有源对象
在 AppsRepo.protectCloudApp() 和 FilesRepo.protectCloudFile() 中添加数据库更新调用
确保 preserveId 被正确保存到数据库,使盾牌图标能够显示
添加 isProtecting 状态跟踪保护操作进度
在操作进行时显示 CircularProgressIndicator 替代盾牌图标
解决了目录重命名失败、盾牌图标不显示、文件大小无法获取等问题
提交: b89f03a

2025-11-15 Section copied!
修复 S3 文件删除功能并统一协议选择 UI 风格

修复 S3 文件删除问题,移除 exists 检查,直接调用 deleteRecursively
为数据库操作添加 try-catch 包裹,防止崩溃
添加详细的删除过程日志追踪
将协议下拉菜单替换为分段按钮行 (SingleChoiceSegmentedButtonRow)
与 FTP 认证模式 UI 保持一致
修复了 S3 文件备份确认删除后不会被删除的问题
提交: c5b1d28 / 8d5d20f

2025-11-15 Section copied!
添加 HTTP/HTTPS 协议选择和动态分块大小计算

添加 S3Protocol 枚举 (HTTP/HTTPS),默认为 HTTPS
实现基于文件大小的动态分块大小计算(最多 10000 个分块,每块最小 5MB)
在 S3 设置页面添加协议选择 UI
支持本地 S3 兼容存储(如 MinIO)使用 HTTP 协议,避免无 SSL 证书时的访问错误
支持大文件上传,不会超过 S3 的 10000 个分块限制
提交: 7f3f1ab