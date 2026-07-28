package com.fuckqq.nullfriend.provider

import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log
import com.fuckqq.nullfriend.util.UinUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * NT 架构好友列表获取。
 *
 * QQ NT 将好友逻辑下沉到 C++ 内核 (libkernel.so)，Java 侧通过
 * com.tencent.qqnt.kernel.api.IKernelService 暴露：
 *
 * 1. BuddyService.getBuddyListV2(callFrom, force, BuddyListReqType.KNOMAL, IBuddyListCallback)
 *    -> 异步回调 onResult(code, msg, ArrayList<BuddyListCategory>)
 *    -> BuddyListCategory.buddyUids : ArrayList<String>  (uid, 形如 u_xxx)
 *
 * 2. ProfileService.getCoreAndBaseInfo("nodeStore", uids)
 *    -> 同步返回 HashMap<String, UserSimpleInfo>
 *    -> UserSimpleInfo.uin : long
 *       UserSimpleInfo.coreInfo.nick / coreInfo.remark : String
 *
 * service 获取：MobileQQ.sMobileQQ -> mAppRuntime -> getRuntimeService(IKernelService.class)
 * 参考 com.tencent.qqnt.g.d() 与 com.tencent.qqnt.msg.f.h()。
 */
object NtBuddyProvider {

    private const val TAG = "NtBuddyProvider"
    private const val CALL_FROM = "FuckQQNullFriend"
    private const val STORE = "nodeStore"

    @Volatile
    private var hostCl: ClassLoader? = null

    /** 缓存的反射元数据，避免每次 fetch 重复查找 */
    private class Meta(
        val clsIKernelService: Class<*>,
        val clsBuddyListReqType: Class<*>,
        val reqTypeNormal: Any,
        val clsIBuddyListCallback: Class<*>,
        val clsBuddyListCategory: Class<*>,
        val clsProfileService: Class<*>,
        val clsUserSimpleInfo: Class<*>,
        val clsCoreInfo: Class<*>
    )

    @Volatile
    private var meta: Meta? = null

    /** 被动收集的 uid 缓存（QQ 自己调 getBuddyListV2 时拦截） */
    @Volatile
    private var cachedUids: List<String> = emptyList()
    @Volatile
    private var cachedAt: Long = 0

    fun install(classLoader: ClassLoader) {
        hostCl = classLoader
        hookBuddyListV2Passive(classLoader)
    }

    /**
     * 被动收集: hook BuddyService.getBuddyListV2，包装 QQ 自己传入的 callback，
     * 在回调触发时缓存 uid 列表。这样即使用户只是打开联系人页，模块也能拿到数据。
     */
    private fun hookBuddyListV2Passive(cl: ClassLoader) {
        val clsBuddyService = XposedHelpers.findClassIfExists(
            "com.tencent.qqnt.kernel.api.impl.BuddyService", cl
        ) ?: return
        val clsIBuddyListCallback = XposedHelpers.findClassIfExists(
            "com.tencent.qqnt.kernel.nativeinterface.IBuddyListCallback", cl
        ) ?: return

        for (m in clsBuddyService.declaredMethods) {
            if (m.name != "getBuddyListV2") continue
            if (m.parameterTypes.lastOrNull() != clsIBuddyListCallback) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val cbIdx = param.args.lastIndex
                            val origCb = param.args[cbIdx] ?: return
                            val wrap = Proxy.newProxyInstance(
                                cl, arrayOf(clsIBuddyListCallback)
                            ) { _, method, args ->
                                if (method.name == "onResult") {
                                    runCatching { cacheFromCallback(args) }
                                }
                                // 透传给原 callback
                                try {
                                    method.invoke(origCb, *args)
                                } catch (_: Throwable) {
                                    null
                                }
                            }
                            param.args[cbIdx] = wrap
                        } catch (t: Throwable) {
                            Log.d("$TAG passive hook wrap: ${t.message}")
                        }
                    }
                })
                Log.i("$TAG passive hook installed on ${m.name}")
            } catch (t: Throwable) {
                Log.d("$TAG passive hook install: ${t.message}")
            }
        }
    }

    private fun cacheFromCallback(args: Array<Any?>) {
        val code = (args.getOrNull(0) as? Number)?.toInt() ?: -1
        if (code != 0) return
        @Suppress("UNCHECKED_CAST")
        val categories = args.getOrNull(2) as? ArrayList<Any?> ?: return
        val uids = ArrayList<String>()
        for (cat in categories) {
            if (cat == null) continue
            val buddyUids = runCatching {
                XposedHelpers.callMethod(cat, "getBuddyUids") as? ArrayList<*>
            }.getOrNull() ?: continue
            for (uid in buddyUids) {
                if (uid is String && uid.isNotBlank()) uids.add(uid)
            }
        }
        if (uids.isNotEmpty()) {
            cachedUids = uids
            cachedAt = System.currentTimeMillis()
            Log.i("$TAG passive cached uids=${uids.size}")
        }
    }

    private fun ensureMeta(cl: ClassLoader): Meta? {
        meta?.let { return it }
        return try {
            val clsIKernelService = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.api.IKernelService", cl
            )
            val clsBuddyListReqType = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType", cl
            )
            val reqTypeNormal = clsBuddyListReqType.enumConstants?.firstOrNull {
                (it as? Enum<*>)?.name == "KNOMAL"
            } ?: clsBuddyListReqType.enumConstants!![0]
            val clsIBuddyListCallback = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.nativeinterface.IBuddyListCallback", cl
            )
            val clsBuddyListCategory = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.nativeinterface.BuddyListCategory", cl
            )
            val clsProfileService = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.api.ad", cl
            )
            val clsUserSimpleInfo = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.nativeinterface.UserSimpleInfo", cl
            )
            val clsCoreInfo = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.nativeinterface.CoreInfo", cl
            )
            Meta(
                clsIKernelService, clsBuddyListReqType, reqTypeNormal,
                clsIBuddyListCallback, clsBuddyListCategory,
                clsProfileService, clsUserSimpleInfo, clsCoreInfo
            ).also { meta = it }
        } catch (t: Throwable) {
            Log.e("$TAG ensureMeta failed", t)
            null
        }
    }

    /**
     * 拿 IKernelService 实例。
     * 路径: MobileQQ.sMobileQQ -> mAppRuntime -> getRuntimeService(IKernelService.class)
     */
    private fun getKernelService(cl: ClassLoader, meta: Meta): Any? {
        // 尝试多种路径，参考 com.tencent.qqnt.g.d()
        val mobileQQ = try {
            val clsMobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", cl)
            XposedHelpers.getStaticObjectField(clsMobileQQ, "sMobileQQ")
        } catch (t: Throwable) {
            Log.d("$TAG MobileQQ.sMobileQQ: ${t.message}")
            return null
        } ?: return null

        // mAppRuntime (com.tencent.common.app.AppInterface 或 mqq.app.AppRuntime)
        val runtime = getAppRuntime(mobileQQ, cl) ?: run {
            Log.d("$TAG appRuntime null")
            return null
        }

        // getRuntimeService(IKernelService.class) — NT 的 @Service 注册机制
        return invokeGetRuntimeService(runtime, meta.clsIKernelService)
            ?: invokeGetService(runtime, meta.clsIKernelService)
            ?: run {
                Log.d("$TAG getRuntimeService(IKernelService) returned null")
                null
            }
    }

    private fun getAppRuntime(mobileQQ: Any, cl: ClassLoader): Any? {
        // 路径1: MobileQQ.mAppRuntime 字段
        runCatching {
            val clsMobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", cl)
            val f = clsMobileQQ.getDeclaredField("mAppRuntime").apply { isAccessible = true }
            f.get(mobileQQ)?.let { return it }
        }
        // 路径2: getMobileQQ().waitAppRuntime(null)
        runCatching {
            val app = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("mqq.app.MobileQQ", cl), "getMobileQQ"
            )
            XposedHelpers.callMethod(app, "waitAppRuntime", null as Any?)
        }?.let { return it }
        // 路径3: MobileQQ 直接当 runtime (某些版本 MobileQQ extends AppRuntime)
        runCatching {
            if (XposedHelpers.findClass("com.tencent.common.app.AppInterface", cl)
                    .isInstance(mobileQQ)
            ) return mobileQQ
        }
        return null
    }

    private fun invokeGetRuntimeService(runtime: Any, serviceClass: Class<*>): Any? {
        // getRuntimeService 可能签名: (Class) 或 (Class, String)
        for (m in runtime.javaClass.methods) {
            if (m.name != "getRuntimeService") continue
            if (m.parameterTypes.size != 1) continue
            if (m.parameterTypes[0] != Class::class.java) continue
            if (!serviceClass.isAssignableFrom(m.returnType) &&
                m.returnType == Any::class.java
            ) {
                // 泛型擦除返回 Object，调用看实际
            }
            return try {
                m.isAccessible = true
                m.invoke(runtime, serviceClass)
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun invokeGetService(runtime: Any, serviceClass: Class<*>): Any? {
        // 备用: getRuntimeService(Class, "all") 或 getManager(IKernelService)
        for (m in runtime.javaClass.methods) {
            if (m.name != "getRuntimeService") continue
            val pts = m.parameterTypes
            if (pts.size == 2 && pts[0] == Class::class.java && pts[1] == String::class.java) {
                return try {
                    m.isAccessible = true
                    m.invoke(runtime, serviceClass, "all")
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }

    /** 调 BuddyService.getBuddyListV2，异步转同步，返回所有分类下的 uid 列表 */
    private fun fetchBuddyUids(
        buddyService: Any,
        meta: Meta,
        timeoutMs: Long
    ): List<String> {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<List<String>>(emptyList())
        val errorRef = AtomicReference<String?>(null)

        // 构造 IBuddyListCallback 代理
        val callback = Proxy.newProxyInstance(
            hostCl,
            arrayOf(meta.clsIBuddyListCallback)
        ) { _, method, args ->
            when (method.name) {
                "onResult" -> {
                    try {
                        // onResult(int code, String msg, ArrayList<BuddyListCategory> categories)
                        val code = (args[0] as? Number)?.toInt() ?: -1
                        val msg = args[1] as? String
                        @Suppress("UNCHECKED_CAST")
                        val categories = args[2] as? ArrayList<Any?>
                        if (code != 0) {
                            errorRef.set("getBuddyListV2 code=$code msg=$msg")
                        } else if (categories == null) {
                            errorRef.set("getBuddyListV2 categories null")
                        } else {
                            val uids = ArrayList<String>()
                            for (cat in categories) {
                                if (cat == null) continue
                                val buddyUids = XposedHelpers.callMethod(cat, "getBuddyUids")
                                    as? ArrayList<*>
                                if (buddyUids != null) {
                                    for (uid in buddyUids) {
                                        if (uid is String && uid.isNotBlank()) uids.add(uid)
                                    }
                                }
                            }
                            resultRef.set(uids)
                        }
                    } catch (t: Throwable) {
                        errorRef.set("onResult parse: ${t.message}")
                    } finally {
                        latch.countDown()
                    }
                }
                "equals", "hashCode", "toString" -> null
                else -> null
            }
            null
        }

        val sigV2: Array<Class<*>> = arrayOf(
            String::class.java,
            Boolean::class.javaPrimitiveType as Class<*>,
            meta.clsBuddyListReqType,
            meta.clsIBuddyListCallback
        )
        val invoked = tryInvoke(
            buddyService, "getBuddyListV2",
            arrayOf(CALL_FROM, true, meta.reqTypeNormal, callback),
            sigV2
        )
        if (!invoked) {
            // 备用签名: (boolean, BuddyListReqType, IBuddyListCallback) 无 callFrom
            val sigV2Short: Array<Class<*>> = arrayOf(
                Boolean::class.javaPrimitiveType as Class<*>,
                meta.clsBuddyListReqType,
                meta.clsIBuddyListCallback
            )
            tryInvoke(
                buddyService, "getBuddyListV2",
                arrayOf(true, meta.reqTypeNormal, callback),
                sigV2Short
            )
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("getBuddyListV2 timeout ${timeoutMs}ms")
        }
        errorRef.get()?.let { throw IllegalStateException(it) }
        return resultRef.get()
    }

    /** 调 ProfileService.getCoreAndBaseInfo，同步返回 UserSimpleInfo map */
    private fun fetchUserSimpleInfo(
        profileService: Any,
        uids: List<String>,
        meta: Meta
    ): Map<String, Any> {
        val uidList = ArrayList(uids)
        // getCoreAndBaseInfo(String callFrom, ArrayList<String> uids) : HashMap<String, UserSimpleInfo>
        for (m in profileService.javaClass.methods) {
            if (m.name != "getCoreAndBaseInfo") continue
            if (m.parameterTypes.size != 2) continue
            if (m.parameterTypes[0] != String::class.java) continue
            if (!List::class.java.isAssignableFrom(m.parameterTypes[1])) continue
            return try {
                m.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (m.invoke(profileService, STORE, uidList) as? Map<String, Any>)
                    ?: emptyMap()
            } catch (t: Throwable) {
                Log.d("$TAG getCoreAndBaseInfo invoke: ${t.message}")
                emptyMap()
            }
        }
        return emptyMap()
    }

    private fun tryInvoke(
        target: Any, name: String, args: Array<Any?>, argTypes: Array<Class<*>>
    ): Boolean {
        return try {
            val m: Method = target.javaClass.getMethod(name, *argTypes)
            m.isAccessible = true
            m.invoke(target, *args)
            true
        } catch (t: Throwable) {
            Log.d("$TAG tryInvoke $name: ${t.message}")
            false
        }
    }

    /**
     * 阻塞获取完整好友列表。
     * @return 好友列表，失败抛异常
     */
    fun fetchBlocking(
        classLoader: ClassLoader,
        ownerUin: String,
        timeoutMs: Long = 15_000L
    ): List<FriendEntry> {
        val cl = classLoader
        val meta = ensureMeta(cl) ?: throw IllegalStateException("NT meta not available")
        val kernelService = getKernelService(cl, meta)
            ?: throw IllegalStateException("IKernelService null, NT not ready")

        // BuddyService
        val buddyService = invokeServiceGetter(kernelService, "getBuddyService")
            ?: throw IllegalStateException("getBuddyService() null")
        // ProfileService
        val profileService = invokeServiceGetter(kernelService, "getProfileService")
            ?: throw IllegalStateException("getProfileService() null")

        Log.i("$TAG got buddy=${buddyService.javaClass.simpleName} profile=${profileService.javaClass.simpleName}")

        // 1. 取 uid 列表（主动调失败则用被动 hook 缓存）
        var uids: List<String> = emptyList()
        var uidSource = "active"
        try {
            uids = fetchBuddyUids(buddyService, meta, timeoutMs)
        } catch (t: Throwable) {
            Log.w("$TAG active fetchBuddyUids failed: ${t.message}")
        }
        if (uids.isEmpty() && cachedUids.isNotEmpty()) {
            uids = cachedUids
            uidSource = "passive-cache"
            Log.i("$TAG using passive cache uids=${uids.size} age=${(System.currentTimeMillis() - cachedAt) / 1000}s")
        }
        Log.i("$TAG buddyUids=${uids.size} src=$uidSource")
        if (uids.isEmpty()) {
            throw IllegalStateException("buddyUids empty (active failed, no cache). 打开QQ联系人页后重试")
        }

        // 2. uid -> UserSimpleInfo
        val infoMap = fetchUserSimpleInfo(profileService, uids, meta)
        Log.i("$TAG userInfoMap=${infoMap.size}")

        // 3. 组装 FriendEntry
        val out = ArrayList<FriendEntry>(infoMap.size)
        for ((_, info) in infoMap) {
            val uin = longField(info, "uin")?.toString() ?: continue
            val norm = UinUtil.normalize(uin) ?: continue
            if (norm == ownerUin) continue
            val coreInfo = runCatching {
                XposedHelpers.callMethod(info, "getCoreInfo")
            }.getOrNull() ?: continue
            val nick = strField(coreInfo, "nick")
            val remark = strField(coreInfo, "remark")
            val display = when {
                !remark.isNullOrBlank() -> remark
                !nick.isNullOrBlank() -> nick
                else -> norm
            }
            out.add(FriendEntry(norm, display, nick, FriendSource.API))
        }
        Log.i("$TAG friends=${out.size}")
        return out.sortedBy { it.uin }
    }

    /** IKernelService.getBuddyService() / getProfileService() — 可能返回混淆名 o / ad */
    private fun invokeServiceGetter(kernelService: Any, getterName: String): Any? {
        // 优先原名
        runCatching {
            val m = kernelService.javaClass.getMethod(getterName)
            m.isAccessible = true
            return m.invoke(kernelService)
        }?.let { return it }
        // 可能被 R8 重命名为单字母，扫无参返回对象的方法也难定位；
        // 但 KernelServiceImpl 未混淆这些 public 接口方法，原名可用。
        return null
    }

    private fun longField(obj: Any, name: String): Long? = try {
        when (val v = XposedHelpers.callMethod(obj, "get${name.replaceFirstChar { it.uppercase() }}")) {
            is Long -> v
            is Number -> v.toLong()
            else -> null
        }
    } catch (_: Throwable) {
        try {
            when (val v = XposedHelpers.getObjectField(obj, name)) {
                is Long -> v
                is Number -> v.toLong()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun strField(obj: Any, name: String): String? = try {
        XposedHelpers.callMethod(obj, "get${name.replaceFirstChar { it.uppercase() }}") as? String
    } catch (_: Throwable) {
        try {
            XposedHelpers.getObjectField(obj, name) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
