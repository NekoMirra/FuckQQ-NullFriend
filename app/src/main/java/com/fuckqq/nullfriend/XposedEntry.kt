package com.fuckqq.nullfriend

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed entry. Static scope is QQ only ([Constants.QQ_PACKAGE]).
 */
class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModuleMain.modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.QQ_PACKAGE) return
        ModuleMain.onQqLoaded(lpparam)
    }
}
