package com.mygallery.app.pro

import android.content.Context

object Customization {
    fun setDarkMode(context:Context,dark:Boolean) = context.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putBoolean("dark",dark).apply()
    fun isDarkMode(context:Context):Boolean = context.getSharedPreferences("ui",Context.MODE_PRIVATE).getBoolean("dark",true)
    /** Widgets and gesture configuration are future modules. */
}
