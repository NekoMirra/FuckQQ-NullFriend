package com.fuckqq.nullfriend

import android.app.Application
import android.content.Context
import com.fuckqq.nullfriend.data.DetectorRepository
import com.fuckqq.nullfriend.data.Prefs
import com.fuckqq.nullfriend.hook.SettingsInjectHook
import com.fuckqq.nullfriend.hook.StartupHook
import com.fuckqq.nullfriend.provider.HybridFriendListProvider
import com.fuckqq.nullfriend.service.DetectionService
import com.fuckqq.nullfriend.service.Notifier
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

object ModuleMain {
    @Volatile
    var modulePath: String = ""

    @Volatile
    var appContext: Context? = null

    @Volatile
    var classLoader: ClassLoader? = null

    private val inited = AtomicBoolean(false)

    lateinit var prefs: Prefs
        private set
    lateinit var repository: DetectorRepository
        private set
    lateinit var detectionService: DetectionService
        private set

    fun onQqLoaded(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!inited.compareAndSet(false, true)) return
        classLoader = lpparam.classLoader
        Log.i("Loading in ${lpparam.packageName} process=${lpparam.processName}")

        hookApplicationCreate(lpparam)
        SettingsInjectHook.install(lpparam)
        StartupHook.install(lpparam)
    }

    private fun hookApplicationCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Application
                        if (app.packageName != Constants.QQ_PACKAGE) return
                        // Prefer main process
                        if (appContext != null) return
                        initWithContext(app.applicationContext)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("hook Application.onCreate failed", t)
        }
    }

    fun initWithContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        prefs = Prefs(context)
        repository = DetectorRepository(context)
        val provider = HybridFriendListProvider(context, classLoader)
        detectionService = DetectionService(
            repository = repository,
            provider = provider,
            prefs = prefs,
            notifier = Notifier(context)
        )
        Log.i("Module services ready")
        detectionService.scheduleStartupCheck()
        detectionService.reschedulePeriodic()
    }
}
