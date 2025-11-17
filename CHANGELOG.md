云端数据同步修复 (2025-11-17)
修复云端重载数据一致性问题 FilesRepo.kt:343-374

确保云端重载操作后,本地数据库记录与远端 S3 存储完全同步
解决了重载后可能出现的数据不一致问题
提交: fc0ee78

S3 受保护操作增强 (2025-11-15)
修复目录重命名逻辑
核心改进: FilesRepo.kt:343-374

修复 S3ClientImpl.renameTo() 方法,从只复制单个对象改为列出并复制目录下的所有对象
使用 listObjectsV2 API 获取源目录前缀下的所有对象
逐个复制每个对象到新目标路径,保持相对路径结构
使用批量删除 API (deleteObjects) 删除所有源对象
解决了 "Failed to query the state of source object" 错误
数据库更新修复
在 AppsRepo.protectCloudApp() 和 FilesRepo.protectCloudFile() 中添加数据库更新调用 FilesRepo.kt:368-370
确保 preserveId 被正确保存到数据库,使盾牌图标能够显示
确保 archivesRelativeDir 自动更新为新路径,使文件大小能够正确获取
用户体验改进
添加 isProtecting 状态跟踪保护操作进度
在操作进行时显示 CircularProgressIndicator 替代盾牌图标
使用 try-finally 块管理加载状态
添加详细的日志输出,便于调试
提交: b89f03a

S3 文件删除功能修复 (2025-11-15)
删除逻辑优化: FilesRepo.kt:394-420

移除 exists 检查,直接调用 deleteRecursively FilesRepo.kt:404
为数据库操作添加 try-catch 包裹,防止崩溃 FilesRepo.kt:409-416
添加详细的删除过程日志追踪 FilesRepo.kt:401-406
修复了 S3 文件备份确认删除后不会被删除的问题
UI 统一: S3Setup.kt:230-247

将协议下拉菜单替换为分段按钮行 (SingleChoiceSegmentedButtonRow) S3Setup.kt:230-247
与 FTP 认证模式 UI 保持一致
提升云存储提供商之间的 UI 一致性
提交: c5b1d28 / 8d5d20f

HTTP/HTTPS 协议选择与动态分块 (2025-11-15)
协议选择功能: S3Setup.kt:81-90

添加 S3Protocol 枚举 (HTTP/HTTPS),默认为 HTTPS S3Setup.kt:82-90
在 S3 设置页面添加协议选择 UI S3Setup.kt:230-247
支持本地 S3 兼容存储(如 MinIO)使用 HTTP 协议,避免无 SSL 证书时的访问错误
添加中英文字符串资源 strings.xml:429-430 strings.xml:417-418
动态分块大小计算:

实现基于文件大小的动态分块大小计算(最多 10000 个分块,每块最小 5MB)
替换固定的 5MB 分块大小为自适应计算
支持大文件上传,不会超过 S3 的 10000 个分块限制
提交: 7f3f1ab