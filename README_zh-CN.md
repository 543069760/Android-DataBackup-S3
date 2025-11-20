<div align="center">  

<span style="font-weight: bold"> <a href="./README.md"> English </a> | <a> 中文 </a> </span>  

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />  

<h1 align="center">数据备份 Revived</h1>  

[![文档](https://img.shields.io/badge/wiki-documentation-forestgreen)](https://DataBackupOfficial.github.io)  
[![下载](https://img.shields.io/github/downloads/543069760/Android-DataBackup-S3/total)](https://github.com/543069760/Android-DataBackup-S3/releases)  
[![GitHub release](https://img.shields.io/github/v/release/543069760/Android-DataBackup-S3?color=orange)](https://github.com/543069760/Android-DataBackup-S3/releases)  
[![许可证](https://img.shields.io/github/license/543069760/Android-DataBackup-S3?color=ff69b4)](./LICENSE)  
[![频道](https://img.shields.io/badge/channel-DataBackup-252850?color=blue&logo=telegram)](https://t.me/dabackupchannel)  
[![群组](https://img.shields.io/badge/group-DataBackup-252850?color=blue&logo=telegram)](https://t.me/databackupchat)

免费开源的数据备份应用

</div>  

## 概述
<a href="https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b" target="_blank"><img src="https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral" alt="Featured｜HelloGitHub" style="width: 250px; height: 54px;" width="250" height="54" /></a>

:star: 基于 [XayahSuSuSu] 的(https://github.com/XayahSuSuSu/Android-DataBackup) 项目而来。

## 使用说明
查看[文档](https://DataBackupOfficial.github.io)。

## 功能特性
* :deciduous_tree: **需要 Root 权限,支持 [Magisk](https://github.com/topjohnwu/Magisk)、[KernelSU](https://github.com/tiann/KernelSU)、[APatch](https://github.com/bmax121/APatch)**

* :cyclone: **多用户支持**

* :cloud: **支持多种云存储协议**

* :sunglasses: **100% 数据完整性保证**

* :zap: **快速**

* :sunny: **简单易用**

* :sparkles: **多版本备份支持**

* :rose: **...**

## 版本对比

### 云存储协议支持对比

| 功能特性 | 旧版本 (DataBackup) | 新版本 (DataBackup Revived 3.0.0) |  
|---------|------------------|----------------------------------|  
| **S3 协议** | ❌ 不支持 | ✅ 支持 HTTP/HTTPS 可选 |  
| **FTP 协议** | ✅ 支持 | ✅ 支持 |  
| **SFTP 协议** | ✅ 支持 | ✅ 支持 |  
| **WebDAV 协议** | ✅ 支持 | ✅ 支持 |  
| **SMB/CIFS 协议** | ✅ 支持多版本 | ✅ 支持多版本 |  
| **本地存储** | ✅ 支持 | ✅ 支持 |

### 多版本备份功能

| 功能特性 | 旧版本 | 新版本 |  
|---------|-------|-------|  
| **同一应用多版本备份** | ❌ 不支持 | ✅ 支持 |  
| **备份时间戳** | ❌ 无 | ✅ 精确到秒 |  
| **历史备份列表** |  ❌ 不支持  | ✅ 显示所有历史版本 |  
| **备份目录结构** | `包名/user_用户ID` | `包名/user_用户ID@时间戳` |  
| **恢复时版本选择** |  ❌ 不支持  | ✅ 可选择任意历史版本 |  
| **备份失败清理** |  ❌ 不支持  | ✅ 自动清理失败备份 |  
| **向后兼容** | N/A | ✅ 兼容旧格式备份 |

### 技术变更

| 项目 | 旧版本 | 新版本                              |  
|------|-------|----------------------------------|  
| **应用包名** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |  
| **版本号** | 2.x.x | **3.0.0** (全新版本)                 |   |

## 截图-S3
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233930_19_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_20_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_21_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233932_22_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233933_23_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112234045_24_20.jpg" width="275px">  
</div>  

## 截图
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">  
</div>  

## 下载
从 [Releases](https://github.com/543069760/Android-DataBackup-S3/releases) 获取 APK。

## 翻译
[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png"  
alt="翻译">](https://hosted.weblate.org/engage/databackup/)

## 贡献者
感谢所有这些优秀的人!

[![贡献者](https://contrib.rocks/image?repo=543069760/Android-DataBackup-S3)](https://github.com/543069760/Android-DataBackup-S3/graphs/contributors)

## 支持
如果您喜欢这个应用并希望帮助它变得更好,欢迎赞助我!

[<img src="./docs/static/img/pp_h_rgb.svg"  
alt="PayPal"  
height="60">](https://paypal.me/XayahSuSuSu)

[<img src="./docs/static/img/afdian.svg"  
alt=爱发电  
height="60">](https://afdian.net/a/XayahSuSuSu)

## 许可证
[GNU General Public License v3.0](./LICENSE)