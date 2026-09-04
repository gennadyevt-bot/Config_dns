package com.config.app

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    var icon: Drawable? = null,
    var isSelected: Boolean = false
)
