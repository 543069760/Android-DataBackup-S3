package com.xayah.core.service.util

sealed class BackupResult {
    data class Success(val snapshotId: String) : BackupResult()
    data class Failed(val error: String) : BackupResult()
    object Cancelled : BackupResult()

    val isSuccess: Boolean
        get() = this is Success

    val isFailed: Boolean
        get() = this is Failed

    val isCancelled: Boolean
        get() = this is Cancelled

    companion object {
        fun success(snapshotId: String) = Success(snapshotId)
        fun failed(error: String) = Failed(error)
        fun cancelled() = Cancelled
    }
}