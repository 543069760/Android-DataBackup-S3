<div align="center">  

<span style="font-weight: bold"> <a> English </a> | <a href="./README_zh-CN.md"> 中文说明 </a> </span>  

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />  

<h1 align="center">DataBackup Revived</h1>

[![Doc](https://img.shields.io/badge/wiki-documentation-forestgreen)](https://DataBackupOfficial.github.io)  
[![Download](https://img.shields.io/github/downloads/543069760/Android-DataBackup-S3/total)](https://github.com/543069760/Android-DataBackup-S3/releases)  
[![GitHub release](https://img.shields.io/github/v/release/543069760/Android-DataBackup-S3?color=orange)](https://github.com/543069760/Android-DataBackup-S3/releases)  
[![License](https://img.shields.io/github/license/543069760/Android-DataBackup-S3?color=ff69b4)](./LICENSE)  
[![Channel](https://img.shields.io/badge/channel-DataBackup-252850?color=blue&logo=telegram)](https://t.me/dabackupchannel)  
[![Chat](https://img.shields.io/badge/group-DataBackup-252850?color=blue&logo=telegram)](https://t.me/databackupchat)

Free and open-source data backup application with multi-version backup support

</div>  

## Overview
<a href="https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b" target="_blank"><img src="https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral" alt="Featured｜HelloGitHub" style="width: 250px; height: 54px;" width="250" height="54" /></a>

:star: Based on [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup) .


## Usage
See [documentation](https://DataBackupOfficial.github.io).

## Features
* :deciduous_tree: **Root needed, support [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU), [APatch](https://github.com/bmax121/APatch)**

* :cyclone: **Multi-user Support**

* :cloud: **Cloud Storage with Multiple Protocols**

* :sunglasses: **100% Data Integrity**

* :zap: **Fast**

* :sunny: **Easy**

* :package: **Multi-Version Backup Support**

* :rose: **...**

## Version Comparison

### Cloud Storage Protocol Support

| Feature | Old Version (DataBackup) | New Version (DataBackup Revived 3.0.0) |  
|---------|--------------------------|----------------------------------------|  
| **S3 Protocol** | ❌ Not supported | ✅ HTTP/HTTPS selectable |  
| **FTP Protocol** | ✅ Supported | ✅ Supported |  
| **SFTP Protocol** | ✅ Supported | ✅ Supported |  
| **WebDAV Protocol** | ✅ Supported | ✅ Supported |  
| **SMB/CIFS Protocol** | ✅ Supported | ✅ Supported |  
| **Local Storage** | ✅ Supported | ✅ Supported |

### Multi-Version Backup

| Feature | Old Version | New Version |  
|---------|-------------|-------------|  
| **Multiple Backups per App** | ❌ Single backup only | ✅ Multiple timestamp-based backups |  
| **Backup History** | ❌ Not supported | ✅ View all historical backups in restore list |  
| **Timestamp Display** | ❌ Not shown | ✅ 3-row layout with timestamp in restore list |  
| **Backup Identification** | ⚠️ Overwrite previous backup | ✅ Each backup identified by timestamp |  
| **Partial Failure Handling** | ⚠️ May leave incomplete files | ✅ Auto-cleanup on failure |  
| **Backward Compatibility** | N/A | ✅ Can restore old backups without timestamp |  

### User Interface Improvements

| Feature | Old Version | New Version |  
|---------|-------------|-------------|  
| **App Name** | "数据备份" / "DataBackup" | "数据备份 Revived" / "DataBackup Revived" |  
| **List Layout** | 2-row layout | ✅ 3-row layout with timestamp (restore mode) |  
| **Version Display** | Horizontal layout | ✅ Vertical layout for better readability |  
| **Dashboard Spacing** | Standard spacing | ✅ Optimized spacing for clarity |  

### Technical Changes

| Feature | Old Version | New Version |  
|---------|-------------|-------------|  
| **Package Name** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |  
| **Version Number** | 2.x.x | **3.0.0** (fresh start) |

## Screenshot-S3
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233930_19_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_20_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_21_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233932_22_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233933_23_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112234045_24_20.jpg" width="275px">  
</div>  

## Screenshot
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">  
</div>  

## Download
Get the APK from the [Releases](https://github.com/543069760/Android-DataBackup-S3/releases).

## Translation
[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png"  
alt="Translation">](https://hosted.weblate.org/engage/databackup/)

## Contributors
Thanks to all these wonderful people!

[![Contributors](https://contrib.rocks/image?repo=XayahSuSuSu/Android-DataBackup)](https://github.com/XayahSuSuSu/Android-DataBackup/graphs/contributors)

## Support
If you enjoy this app and want to help it become better, feel free to sponsor me!

[<img src="./docs/static/img/pp_h_rgb.svg"  
alt="PayPal"  
height="60">](https://paypal.me/XayahSuSuSu)

[<img src="./docs/static/img/afdian.svg"  
alt=爱发电  
height="60">](https://afdian.net/a/XayahSuSuSu)

## LICENSE
[GNU General Public License v3.0](./LICENSE)