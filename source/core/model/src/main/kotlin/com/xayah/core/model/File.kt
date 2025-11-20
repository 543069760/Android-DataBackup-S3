package com.xayah.core.model

data class File(
    val id: Long,
    val name: String,
    val path: String,
    val preserveId: Long,
    val selected: Boolean,
    val backupTimestamp: Long = 0L,  // 新增
    val isProtected: Boolean = false  // 新增
)
