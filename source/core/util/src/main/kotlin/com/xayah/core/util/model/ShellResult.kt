package com.xayah.core.util.model

data class ShellResult(
    var code: Int,           // 改为 var
    var input: List<String>, // 改为 var
    var out: List<String>    // 改为 var
) {
    val isSuccess: Boolean get() = code == 0
    val inputString: String get() = input.joinToString("\n")
    val outString: String get() = out.joinToString("\n")
}