package com.xayah.core.database

import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    @RenameColumn(
        tableName = "DirectoryEntity",
        fromColumnName = "directoryType",
        toColumnName = "opType",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "apkLog",
        toColumnName = "apk_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userLog",
        toColumnName = "user_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userDeLog",
        toColumnName = "userDe_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "dataLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "obbLog",
        toColumnName = "obb_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "mediaLog",
        toColumnName = "media_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "apkState",
        toColumnName = "apk_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userState",
        toColumnName = "user_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userDeState",
        toColumnName = "userDe_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "dataState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "obbState",
        toColumnName = "obb_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "mediaState",
        toColumnName = "media_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "apkLog",
        toColumnName = "apk_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userLog",
        toColumnName = "user_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userDeLog",
        toColumnName = "userDe_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "dataLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "obbLog",
        toColumnName = "obb_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "mediaLog",
        toColumnName = "media_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "apkState",
        toColumnName = "apk_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userState",
        toColumnName = "user_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userDeState",
        toColumnName = "userDe_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "dataState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "obbState",
        toColumnName = "obb_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "mediaState",
        toColumnName = "media_state",
    )
    @RenameColumn(
        tableName = "MediaBackupOperationEntity",
        fromColumnName = "opLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "MediaBackupOperationEntity",
        fromColumnName = "opState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "MediaBackupOperationEntity",
        fromColumnName = "state",
        toColumnName = "mediaState",
    )
    @RenameColumn(
        tableName = "MediaRestoreOperationEntity",
        fromColumnName = "opLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "MediaRestoreOperationEntity",
        fromColumnName = "opState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "MediaRestoreOperationEntity",
        fromColumnName = "state",
        toColumnName = "mediaState",
    )
    class Schema2to3 : AutoMigrationSpec

    @DeleteTable(
        tableName = "LogEntity"
    )
    @DeleteColumn(
        tableName = "DirectoryEntity",
        columnName = "opType"
    )
    @DeleteTable(
        tableName = "TaskEntity"
    )
    @DeleteTable(
        tableName = "CmdEntity"
    )
    @DeleteTable(
        tableName = "PackageBackupEntire"
    )
    @DeleteTable(
        tableName = "PackageBackupOperation"
    )
    @DeleteTable(
        tableName = "PackageRestoreEntire"
    )
    @DeleteTable(
        tableName = "PackageRestoreOperation"
    )
    @DeleteTable(
        tableName = "MediaBackupEntity"
    )
    @DeleteTable(
        tableName = "MediaBackupOperationEntity"
    )
    @DeleteTable(
        tableName = "MediaRestoreEntity"
    )
    @DeleteTable(
        tableName = "MediaRestoreOperationEntity"
    )
    @DeleteTable(
        tableName = "CloudEntity"
    )
    class Schema3to4 : AutoMigrationSpec

    @DeleteColumn(
        tableName = "TaskDetailPackageEntity",
        columnName = "packageEntity_extraInfo_existed"
    )
    @DeleteColumn(
        tableName = "PackageEntity",
        columnName = "extraInfo_existed"
    )
    @DeleteColumn(
        tableName = "PackageEntity",
        columnName = "extraInfo_labels"
    )
    @DeleteColumn(
        tableName = "MediaEntity",
        columnName = "extraInfo_labels"
    )
    @DeleteColumn(
        tableName = "TaskDetailPackageEntity",
        columnName = "packageEntity_extraInfo_labels"
    )
    @DeleteColumn(
        tableName = "TaskDetailMediaEntity",
        columnName = "mediaEntity_extraInfo_labels"
    )
    class Schema5to6 : AutoMigrationSpec

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 添加 backupTimestamp 列到 PackageEntity
            database.execSQL(
                "ALTER TABLE PackageEntity ADD COLUMN indexInfo_backupTimestamp INTEGER NOT NULL DEFAULT 0"
            )

            // 添加 isProtected 列到 PackageEntity
            database.execSQL(
                "ALTER TABLE PackageEntity ADD COLUMN extraInfo_isProtected INTEGER NOT NULL DEFAULT 0"
            )

            // 添加 backupTimestamp 列到 MediaEntity
            database.execSQL(
                "ALTER TABLE MediaEntity ADD COLUMN indexInfo_backupTimestamp INTEGER NOT NULL DEFAULT 0"
            )

            // 添加 isProtected 列到 MediaEntity
            database.execSQL(
                "ALTER TABLE MediaEntity ADD COLUMN extraInfo_isProtected INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 创建 UploadIdEntity 表
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS UploadIdEntity (  
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,  
                uploadId TEXT NOT NULL,  
                bucket TEXT NOT NULL,  
                key TEXT NOT NULL,  
                timestamp INTEGER NOT NULL,  
                cloudName TEXT NOT NULL  
            )"""
            )
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 添加 isCanceled 列到 PackageEntity
            database.execSQL(
                "ALTER TABLE PackageEntity ADD COLUMN extraInfo_isCanceled INTEGER NOT NULL DEFAULT 0"
            )

            // 添加 isCanceled 列到 MediaEntity
            database.execSQL(
                "ALTER TABLE MediaEntity ADD COLUMN extraInfo_isCanceled INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}