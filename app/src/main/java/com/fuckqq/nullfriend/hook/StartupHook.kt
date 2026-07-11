package com.fuckqq.nullfriend.hook

import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.callbacks.XC_LoadPackage

object StartupHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        Log.d("StartupHook registered for ${lpparam.packageName}")
        // Actual schedule happens in ModuleMain.initWithContext → DetectionService
    }
}
