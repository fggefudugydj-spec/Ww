package com.example.launcher

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Window
import com.example.core.LeviLogger
import com.example.engine.AxmlManifestParser
import com.example.engine.DexInspector
import com.example.engine.Elf64Parser
import com.example.engine.NativeLibraryManager
import com.example.model.AxmlManifestSummary
import com.example.model.ElfInspectionResult
import com.example.model.InstalledMinecraftPackage
import com.example.model.LibraryLoadSource
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ApkHostContextWrapper(
    base: Context,
    private val hostActivity: Activity?,
    private val hostAssets: AssetManager,
    private val hostResources: Resources,
    private val hostClassLoader: ClassLoader,
    private val targetPkg: InstalledMinecraftPackage,
    private val nativeLibDirPath: String,
    private val sandboxDataRoot: File,
    private val sandboxFilesDir: File,
    private val sandboxCacheDir: File,
    private val sandboxExternalFilesDir: File,
    private val targetThemeResId: Int = 0,
    private val activityMetaData: Bundle = Bundle().apply {
        putString("android.app.lib_name", "minecraftpe")
    },
    private val hostedActivityClassName: String = targetPkg.launchActivityName ?: "com.mojang.minecraftpe.MainActivity",
    private val archiveAppInfo: ApplicationInfo? = null,
    private val axmlSummary: AxmlManifestSummary? = null
) : ContextThemeWrapper(base, 0) {

    @Volatile
    private var attachedApplicationContext: Context? = null

    private val hostLayoutInflater: LayoutInflater by lazy {
        LayoutInflater.from(base).cloneInContext(this)
    }

    private val hostTheme: Resources.Theme by lazy {
        hostResources.newTheme().apply {
            val resolvedRes = if (targetThemeResId != 0) {
                targetThemeResId
            } else {
                android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
            }
            val applied = runCatching { applyStyle(resolvedRes, true) }.isSuccess
            if (!applied && resolvedRes != android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
                runCatching { applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen, true) }
            }
        }
    }

    private val patchedAppInfo: ApplicationInfo by lazy {
        ApplicationInfo(base.applicationInfo).apply {
            packageName = targetPkg.packageName
            processName = targetPkg.packageName
            uid = base.applicationInfo.uid
            enabled = true
            taskAffinity = targetPkg.packageName
            val rawFlags = archiveAppInfo?.flags ?: base.applicationInfo.flags
            flags = (rawFlags or ApplicationInfo.FLAG_HAS_CODE) and
                ApplicationInfo.FLAG_DEBUGGABLE.inv() and
                ApplicationInfo.FLAG_TEST_ONLY.inv()
            archiveAppInfo?.className?.takeIf { it.isNotBlank() }?.let { className = it }
                ?: axmlSummary?.applicationClassName?.takeIf { it.isNotBlank() }?.let { className = it }
            archiveAppInfo?.manageSpaceActivityName?.takeIf { it.isNotBlank() }?.let {
                manageSpaceActivityName = it
            }
            sourceDir = targetPkg.sourceDir
            publicSourceDir = targetPkg.sourceDir
            splitSourceDirs = targetPkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
            splitPublicSourceDirs = targetPkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
            splitNames = targetPkg.splitNames.takeIf { it.isNotEmpty() }?.toTypedArray()
                ?: archiveAppInfo?.splitNames
            nativeLibraryDir = nativeLibDirPath
            dataDir = sandboxDataRoot.absolutePath
            metaData = Bundle(activityMetaData)
            nonLocalizedLabel = targetPkg.appLabel
            labelRes = 0
            icon = 0
            targetSdkVersion = archiveAppInfo?.targetSdkVersion?.takeIf { it > 0 }
                ?: axmlSummary?.targetSdkVersion
                ?: targetPkg.targetSdkVersion.takeIf { it > 0 }
                ?: base.applicationInfo.targetSdkVersion
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                minSdkVersion = archiveAppInfo?.minSdkVersion?.takeIf { it > 0 }
                    ?: axmlSummary?.minSdkVersion
                    ?: targetPkg.minSdkVersion.takeIf { it > 0 }
                    ?: base.applicationInfo.minSdkVersion
                deviceProtectedDataDir = sandboxDataRoot.absolutePath
            }
            if (targetThemeResId != 0) {
                theme = targetThemeResId
            } else if ((archiveAppInfo?.theme ?: 0) != 0) {
                theme = archiveAppInfo!!.theme
            }
        }
    }

    private val sandboxPm: PackageManager by lazy {
        SandboxPackageManager(
            basePm = base.packageManager,
            targetPkg = targetPkg,
            patchedAppInfo = patchedAppInfo,
            hostResources = hostResources,
            activityMetaData = activityMetaData,
            hostedActivityClassName = hostedActivityClassName
        )
    }

    internal fun attachApplicationContext(app: Context) {
        attachedApplicationContext = app
    }

    override fun getAssets(): AssetManager = hostAssets

    override fun getResources(): Resources = hostResources

    override fun getTheme(): Resources.Theme = hostTheme

    override fun getClassLoader(): ClassLoader = hostClassLoader

    override fun getPackageName(): String = targetPkg.packageName

    override fun getOpPackageName(): String = baseContext.packageName

    override fun getPackageManager(): PackageManager = sandboxPm

    override fun getPackageCodePath(): String = targetPkg.sourceDir

    override fun getPackageResourcePath(): String = targetPkg.sourceDir

    override fun getFilesDir(): File = sandboxFilesDir.apply { mkdirs() }

    override fun getNoBackupFilesDir(): File =
        File(sandboxDataRoot, "no_backup").apply { mkdirs() }

    override fun getCacheDir(): File = sandboxCacheDir.apply { mkdirs() }

    override fun getCodeCacheDir(): File =
        File(sandboxDataRoot, "code_cache").apply { mkdirs() }

    override fun getExternalFilesDir(type: String?): File =
        if (type.isNullOrBlank()) {
            sandboxExternalFilesDir.apply { mkdirs() }
        } else {
            File(sandboxExternalFilesDir, type).apply { mkdirs() }
        }

    override fun getExternalFilesDirs(type: String?): Array<File> =
        arrayOf(getExternalFilesDir(type))

    override fun getDataDir(): File = sandboxDataRoot.apply { mkdirs() }

    override fun getExternalCacheDir(): File =
        File(sandboxCacheDir, "external").apply { mkdirs() }

    override fun getExternalCacheDirs(): Array<File> =
        arrayOf(externalCacheDir)

    override fun getObbDir(): File =
        File(sandboxExternalFilesDir, "obb").apply { mkdirs() }

    override fun getObbDirs(): Array<File> =
        arrayOf(obbDir)

    override fun getDatabasePath(name: String): File {
        val dbDir = File(sandboxDataRoot, "databases").apply { mkdirs() }
        return File(dbDir, name)
    }

    override fun getApplicationInfo(): ApplicationInfo = patchedAppInfo

    override fun getApplicationContext(): Context = attachedApplicationContext ?: this

    override fun createPackageContext(packageName: String?, flags: Int): Context {
        if (packageName == targetPkg.packageName) return this
        return super.createPackageContext(packageName, flags)
    }

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
        val derived = super.createConfigurationContext(overrideConfiguration)
        @Suppress("DEPRECATION")
        val derivedResources = Resources(
            hostAssets,
            hostResources.displayMetrics,
            Configuration(hostResources.configuration).apply { updateFrom(overrideConfiguration) }
        )
        return deriveChildWrapper(derived, derivedResources)
    }

    override fun createDisplayContext(display: Display): Context {
        val derived = super.createDisplayContext(display)
        return deriveChildWrapper(derived, hostResources)
    }

    override fun createContextForSplit(splitName: String?): Context {
        return this
    }

    private fun deriveChildWrapper(derivedBase: Context, resources: Resources): ApkHostContextWrapper {
        return ApkHostContextWrapper(
            base = derivedBase,
            hostActivity = hostActivity,
            hostAssets = hostAssets,
            hostResources = resources,
            hostClassLoader = hostClassLoader,
            targetPkg = targetPkg,
            nativeLibDirPath = nativeLibDirPath,
            sandboxDataRoot = sandboxDataRoot,
            sandboxFilesDir = sandboxFilesDir,
            sandboxCacheDir = sandboxCacheDir,
            sandboxExternalFilesDir = sandboxExternalFilesDir,
            targetThemeResId = targetThemeResId,
            activityMetaData = activityMetaData,
            hostedActivityClassName = hostedActivityClassName,
            archiveAppInfo = archiveAppInfo,
            axmlSummary = axmlSummary
        ).also { child ->
            attachedApplicationContext?.let { child.attachApplicationContext(it) }
        }
    }

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            return hostLayoutInflater
        }
        return hostActivity?.getSystemService(name) ?: super.getSystemService(name)
    }
}

private class SandboxHostApplication(base: Context) : Application() {
    init {
        attachBaseContext(base)
    }
}

class HostedActivityHandle(
    val hostedActivity: Activity,
    val hostContext: ApkHostContextWrapper,
    val classLoader: ClassLoader,
    val activityClassName: String,
    val mountedAssetCount: Int,
    val loadedNativeLibraries: List<String>,
    val reflectionWarnings: List<String>,
    private val onDestroyCleanup: () -> Unit = {}
) {
    fun dispatchStart() {
        invokeLifecycleMethod("onStart")
    }

    fun dispatchResume() {
        invokeLifecycleMethod("onResume")
        invokeLifecycleMethod("onPostResume")
    }

    fun dispatchPause() {
        invokeLifecycleMethod("onPause")
    }

    fun dispatchStop() {
        invokeLifecycleMethod("onStop")
    }

    fun dispatchDestroy() {
        invokeLifecycleMethod("onDestroy")
        runCatching { onDestroyCleanup() }
    }

    fun dispatchLowMemory() {
        runCatching { hostedActivity.onLowMemory() }
    }

    fun dispatchTrimMemory(level: Int) {
        runCatching { hostedActivity.onTrimMemory(level) }
    }

    fun dispatchWindowFocusChanged(hasFocus: Boolean) {
        runCatching { hostedActivity.onWindowFocusChanged(hasFocus) }
    }

    fun dispatchConfigurationChanged(newConfig: Configuration) {
        runCatching { hostedActivity.onConfigurationChanged(newConfig) }
    }

    fun dispatchNewIntent(intent: Intent) {
        runCatching {
            val m = findMethodInHierarchy(hostedActivity.javaClass, "onNewIntent", Intent::class.java)
            m?.apply { isAccessible = true }?.invoke(hostedActivity, intent)
        }
    }

    fun dispatchBackPressed(): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            hostedActivity.onBackPressed()
            true
        }.getOrDefault(false)
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return runCatching { hostedActivity.dispatchKeyEvent(event) }.getOrDefault(false)
    }

    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return runCatching { hostedActivity.dispatchTouchEvent(event) }.getOrDefault(false)
    }

    private fun invokeLifecycleMethod(methodName: String) {
        runCatching {
            val m = findMethodInHierarchy(hostedActivity.javaClass, methodName)
            m?.apply { isAccessible = true }?.invoke(hostedActivity)
        }
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var cur: Class<*>? = cls
        while (cur != null) {
            runCatching { cur.getDeclaredMethod(name, *paramTypes) }.getOrNull()?.let { return it }
            cur = cur.superclass
        }
        return null
    }
}

data class HostPreparedResources(
    val assets: AssetManager,
    val resources: Resources,
    val mountedApkCount: Int,
    val topAssetEntries: List<String>,
    val methodUsed: String
)

object InProcessApkHostEngine {

    @Volatile
    private var hiddenApiUnlocked = false

    @Volatile
    var lastDiagnosticSnapshot: String = ""
        private set

    fun isolatedFrameworkParentClassLoader(): ClassLoader {
        val sys = ClassLoader.getSystemClassLoader()
        return sys.parent ?: sys
    }

    fun enableHiddenApiExemptions(logger: LeviLogger? = null): Boolean {
        if (hiddenApiUnlocked) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            hiddenApiUnlocked = true
            return true
        }
        val ok = runCatching {
            val forNameMethod = Class::class.java.getDeclaredMethod(
                "forName",
                String::class.java
            )
            val getDeclaredMethodMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                emptyArray<Class<*>>().javaClass
            )
            val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethodMethod.invoke(
                vmRuntimeClass,
                "getRuntime",
                emptyArray<Class<*>>()
            ) as Method
            val setHiddenApiExemptions = getDeclaredMethodMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(arrayOf<String>().javaClass)
            ) as Method
            val runtime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
            true
        }.onFailure { err ->
            logger?.warn("HOST_HIDDEN_API", "VMRuntime exemption skipped: ${err.javaClass.simpleName}: ${err.message}")
        }.getOrDefault(false)
        if (ok) {
            hiddenApiUnlocked = true
            logger?.info("HOST_HIDDEN_API", "Hidden API reflection exemptions active for Android ${Build.VERSION.SDK_INT}")
        }
        return ok
    }

    fun prepareAssetsAndResources(
        context: Context,
        pkg: InstalledMinecraftPackage,
        logger: LeviLogger? = null
    ): HostPreparedResources {
        enableHiddenApiExemptions(logger)
        val apkPaths = collectReadableApkPaths(pkg)
        require(apkPaths.isNotEmpty()) {
            "No readable Base/Split APK found for ${pkg.packageName}"
        }

        // For Vault APKs, mount an isolated AssetManager directly from the Vault APK + splits first
        // so ResourcesManager cannot mix in LeviLab overlays or phone-installed Minecraft packages.
        if (pkg.isVaultApk) {
            val isolated = tryMountIsolatedAssetManager(context, apkPaths, logger)
            if (isolated != null) {
                return isolated
            }
        }

        val officialRes = runCatching {
            val syntheticApp = ApplicationInfo(context.applicationInfo).apply {
                packageName = pkg.packageName
                sourceDir = pkg.sourceDir
                publicSourceDir = pkg.sourceDir
                splitSourceDirs = pkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
                splitPublicSourceDirs = pkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
            }
            val r = context.packageManager.getResourcesForApplication(syntheticApp)
            val rootEntries = r.assets.list("")?.toList().orEmpty()
            HostPreparedResources(
                assets = r.assets,
                resources = r,
                mountedApkCount = apkPaths.size,
                topAssetEntries = rootEntries,
                methodUsed = "PackageManager.getResourcesForApplication"
            )
        }.getOrNull()

        if (officialRes != null && officialRes.topAssetEntries.isNotEmpty()) {
            logger?.info(
                "HOST_ASSETS",
                "Mounted ${officialRes.mountedApkCount} APK(s) via ${officialRes.methodUsed} (roots=${officialRes.topAssetEntries.size})"
            )
            return officialRes
        }

        return tryMountIsolatedAssetManager(context, apkPaths, logger)
            ?: throw IllegalStateException("AssetManager.addAssetPath could not mount any APK paths for ${pkg.packageName}")
    }

    private fun tryMountIsolatedAssetManager(
        context: Context,
        apkPaths: List<String>,
        logger: LeviLogger?
    ): HostPreparedResources? {
        return runCatching {
            val am = AssetManager::class.java.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()
            val addAssetPathMethod = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
            }

            var mountedCount = 0
            for (path in apkPaths) {
                val cookie = (addAssetPathMethod.invoke(am, path) as? Int) ?: 0
                if (cookie > 0) {
                    mountedCount++
                } else {
                    logger?.warn("HOST_ASSETS", "addAssetPath returned cookie=$cookie for $path")
                }
            }
            if (mountedCount <= 0) return@runCatching null

            @Suppress("DEPRECATION")
            val res = Resources(am, context.resources.displayMetrics, context.resources.configuration)
            val rootEntries = runCatching { am.list("")?.toList().orEmpty() }.getOrDefault(emptyList())
            logger?.info(
                "HOST_ASSETS",
                "Mounted $mountedCount/${apkPaths.size} APK(s) via AssetManager.addAssetPath (roots=${rootEntries.size})"
            )
            HostPreparedResources(
                assets = am,
                resources = res,
                mountedApkCount = mountedCount,
                topAssetEntries = rootEntries,
                methodUsed = "AssetManager.addAssetPath"
            )
        }.getOrNull()
    }

    fun buildSandboxContext(
        baseContext: Context,
        hostActivity: Activity?,
        pkg: InstalledMinecraftPackage,
        preparedResources: HostPreparedResources,
        classLoader: ClassLoader,
        nativeLibDirPath: String,
        targetThemeResId: Int = 0,
        activityMetaData: Bundle = Bundle().apply {
            putString("android.app.lib_name", "minecraftpe")
        },
        hostedActivityClassName: String = pkg.launchActivityName ?: "com.mojang.minecraftpe.MainActivity",
        archiveAppInfo: ApplicationInfo? = null,
        axmlSummary: AxmlManifestSummary? = null
    ): ApkHostContextWrapper {
        val profileKey = buildProfileKey(pkg)
        val sandboxRoot = File(baseContext.filesDir, "mc_sandbox/$profileKey").apply { mkdirs() }
        val sandboxFiles = File(sandboxRoot, "files").apply { mkdirs() }
        val sandboxCache = File(baseContext.cacheDir, "mc_sandbox/$profileKey").apply { mkdirs() }
        val sandboxExtBase = baseContext.getExternalFilesDir(null) ?: sandboxFiles
        val sandboxExternalFiles = File(sandboxExtBase, "mc_sandbox/$profileKey/files").apply { mkdirs() }

        return ApkHostContextWrapper(
            base = baseContext,
            hostActivity = hostActivity,
            hostAssets = preparedResources.assets,
            hostResources = preparedResources.resources,
            hostClassLoader = classLoader,
            targetPkg = pkg,
            nativeLibDirPath = nativeLibDirPath,
            sandboxDataRoot = sandboxRoot,
            sandboxFilesDir = sandboxFiles,
            sandboxCacheDir = sandboxCache,
            sandboxExternalFilesDir = sandboxExternalFiles,
            targetThemeResId = targetThemeResId,
            activityMetaData = activityMetaData,
            hostedActivityClassName = hostedActivityClassName,
            archiveAppInfo = archiveAppInfo,
            axmlSummary = axmlSummary
        )
    }

    fun buildApkClassLoader(
        context: Context,
        pkg: InstalledMinecraftPackage,
        libraries: List<ElfInspectionResult>,
        parentClassLoader: ClassLoader = isolatedFrameworkParentClassLoader()
    ): Pair<DexClassLoader, String> {
        val apkPaths = collectReadableApkPaths(pkg)
        require(apkPaths.isNotEmpty()) {
            "Cannot create DexClassLoader: no readable APK files"
        }

        // Enforce read-only APK files when stored under LeviLab's private filesDir (Android 14+ W^X rule)
        val filesRoot = context.filesDir.canonicalPath
        for (path in apkPaths) {
            val f = File(path)
            if (runCatching { f.canonicalPath.startsWith(filesRoot) }.getOrDefault(false)) {
                f.setReadOnly()
            }
        }

        val profileKey = buildProfileKey(pkg)
        val dexOptDir = File(context.codeCacheDir, "mc_dex/$profileKey").apply { mkdirs() }

        val activeCacheDir = NativeLibraryManager.targetLibDir(context, pkg).apply { mkdirs() }.absolutePath
        // Ensure extracted .so files are readable by the dynamic linker
        File(activeCacheDir).listFiles { f -> f.isFile && f.name.endsWith(".so") }?.forEach { libFile ->
            runCatching { libFile.setReadable(true, false) }
        }

        val directZipDirs = libraries
            .filter { it.loadSource == LibraryLoadSource.APK_DIRECT_ZIP && it.loadablePath.contains("!/") }
            .map { it.loadablePath.substringBeforeLast('/') }
            .distinct()

        val searchPaths = buildList {
            if (pkg.nativeLibraryDir.isNotBlank() && File(pkg.nativeLibraryDir).isDirectory) {
                add(pkg.nativeLibraryDir)
            }
            addAll(directZipDirs)
            add(activeCacheDir)
        }.distinct().joinToString(File.pathSeparator)

        val classLoader = DexClassLoader(
            apkPaths.joinToString(File.pathSeparator),
            dexOptDir.absolutePath,
            searchPaths,
            parentClassLoader
        )
        return classLoader to searchPaths
    }

    fun resolveNativeLoadOrder(libraries: List<ElfInspectionResult>): List<String> {
        val validByName = libraries
            .filter { it.isValid && File(it.filePath).isFile }
            .associateBy { it.fileName }
        require(validByName.containsKey("libminecraftpe.so")) {
            "Cannot resolve native libs: valid ELF64 libminecraftpe.so is missing"
        }
        val fileMap = validByName.mapValues { File(it.value.filePath) }
        val depResolution = Elf64Parser.resolveLoadOrder("libminecraftpe.so", fileMap)
        if (depResolution.missingDependencies.isNotEmpty()) {
            throw UnsatisfiedLinkError(
                "Missing non-system native dependencies: ${depResolution.missingDependencies.joinToString(", ")}"
            )
        }
        return depResolution.bundledLoadOrder
    }

    /**
     * Loads native libraries bound strictly to [targetClass]'s ClassLoader (DexClassLoader) so
     * JNI_OnLoad FindClass resolves Minecraft classes instead of LeviLab classes.
     * Never calls System.load() from LeviLab's ClassLoader; if Runtime.load0 is blocked on a ROM,
     * lets MainActivity's own System.loadLibrary() resolve via DexClassLoader librarySearchPath.
     */
    fun preloadNativeLibrariesForClass(
        targetClass: Class<*>,
        libraries: List<ElfInspectionResult>,
        logger: LeviLogger? = null
    ): List<String> {
        val validByName = libraries
            .filter { it.isValid && File(it.filePath).isFile }
            .associateBy { it.fileName }
        val loadOrder = resolveNativeLoadOrder(libraries)

        val load0Method = runCatching {
            findMethodInHierarchy(
                Runtime::class.java,
                "load0",
                Class::class.java,
                String::class.java
            )
        }.getOrNull()

        if (load0Method == null) {
            logger?.info(
                "HOST_NATIVE",
                "Runtime.load0 unavailable; delegating ${loadOrder.size} ARM64 libs to ${targetClass.simpleName} via DexClassLoader librarySearchPath"
            )
            return loadOrder
        }

        val runtime = Runtime.getRuntime()
        val loaded = mutableListOf<String>()
        for (libName in loadOrder) {
            val info = validByName[libName] ?: continue
            val candidatePath = when (info.loadSource) {
                LibraryLoadSource.NATIVE_DIR -> {
                    val nativeFile = File(info.loadablePath)
                    if (nativeFile.isFile) nativeFile.absolutePath else info.filePath
                }
                else -> info.filePath
            }
            logger?.info("HOST_NATIVE", "Runtime.load0(${targetClass.simpleName}, $libName) <- $candidatePath")
            val loadAttempt = runCatching {
                load0Method.invoke(runtime, targetClass, candidatePath)
            }
            if (loadAttempt.isSuccess) {
                loaded.add(libName)
            } else {
                val err = loadAttempt.exceptionOrNull()
                val root = generateSequence(err) { it.cause }.lastOrNull() ?: err
                if (root is SecurityException || root is IllegalAccessException || root is NoSuchMethodException) {
                    logger?.warn(
                        "HOST_NATIVE",
                        "Runtime.load0 blocked (${root.javaClass.simpleName}); remaining libs will load via ${targetClass.simpleName}"
                    )
                    return if (loaded.isEmpty()) loadOrder else (loaded + loadOrder).distinct()
                }
                if (root != null) throw root
            }
        }
        return loaded
    }

    fun launchHostedMainActivity(
        hostActivity: Activity,
        pkg: InstalledMinecraftPackage,
        libraries: List<ElfInspectionResult>,
        logger: LeviLogger? = null,
        onBeforeActivityOnCreate: (mountedAssetCount: Int, loadedLibsCount: Int, activityClassName: String) -> Unit = { _, _, _ -> }
    ): HostedActivityHandle {
        enableHiddenApiExemptions(logger)

        val preparedAssets = prepareAssetsAndResources(hostActivity, pkg, logger)
        val (dexClassLoader, nativeSearchPath) = buildApkClassLoader(
            context = hostActivity,
            pkg = pkg,
            libraries = libraries,
            parentClassLoader = isolatedFrameworkParentClassLoader()
        )

        val axml = if (pkg.sourceDir.isNotBlank()) {
            AxmlManifestParser.parseFromApk(File(pkg.sourceDir))
        } else {
            null
        }
        val archiveInfo = if (pkg.sourceDir.isNotBlank()) {
            val baseFlags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_META_DATA or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_GIDS
            val fullFlags = baseFlags or PackageManager.GET_SIGNATURES or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
                )
            fun parseArchive(flags: Int): PackageInfo? = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hostActivity.packageManager.getPackageArchiveInfo(
                        pkg.sourceDir,
                        PackageManager.PackageInfoFlags.of(flags.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    hostActivity.packageManager.getPackageArchiveInfo(pkg.sourceDir, flags)
                }
            }.getOrNull()

            parseArchive(fullFlags)
                ?: parseArchive(baseFlags)
                ?: parseArchive(PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA)
        } else {
            null
        }

        val activityMetaData = Bundle().apply {
            putString("android.app.lib_name", "minecraftpe")
            axml?.metaData?.forEach { (k, v) ->
                putString(k, v)
            }
        }

        val candidateActivities = buildList {
            pkg.launchActivityName?.takeIf { it.isNotBlank() }?.let { add(it) }
            axml?.activities?.let { addAll(it) }
            archiveInfo?.activities?.mapNotNull { it.name }?.let { addAll(it) }
            add("com.mojang.minecraftpe.MainActivity")
            add("com.mojang.minecraftpe.preview.MainActivity")
            add("com.mojang.minecraftpe.GameActivity")
        }.distinct()

        // Load Activity class WITHOUT running <clinit> yet
        var loadedClass: Class<*>? = null
        var lastClassError: Throwable? = null
        for (className in candidateActivities) {
            try {
                val cls = Class.forName(className, false, dexClassLoader)
                if (Activity::class.java.isAssignableFrom(cls)) {
                    loadedClass = cls
                    break
                }
            } catch (t: Throwable) {
                lastClassError = t
            }
        }
        val activityClass = loadedClass
            ?: throw (lastClassError ?: ClassNotFoundException(
                "No launchable Activity class resolved among ${candidateActivities.joinToString(", ")}"
            ))

        val matchedArchiveActivity = archiveInfo?.activities?.firstOrNull {
            it.name == activityClass.name || it.name.endsWith(".${activityClass.simpleName}")
        }
        matchedArchiveActivity?.metaData?.let { meta ->
            activityMetaData.putAll(meta)
        }
        val resolvedAppClassName = axml?.applicationClassName?.takeIf { it.isNotBlank() }
            ?: archiveInfo?.applicationInfo?.className?.takeIf { it.isNotBlank() }
        val resolvedThemeRes = matchedArchiveActivity?.theme?.takeIf { it != 0 }
            ?: archiveInfo?.applicationInfo?.theme?.takeIf { it != 0 }
            ?: (axml?.applicationThemeRes ?: 0)

        val nativeLibPrimaryDir = nativeSearchPath
            .split(File.pathSeparatorChar)
            .firstOrNull { !it.contains("!/") && File(it).isDirectory }
            ?: NativeLibraryManager.targetLibDir(hostActivity, pkg).absolutePath

        // 1. Build isolated SandboxContext FIRST (before Application & Activity init)
        val sandboxContext = buildSandboxContext(
            baseContext = hostActivity,
            hostActivity = hostActivity,
            pkg = pkg,
            preparedResources = preparedAssets,
            classLoader = dexClassLoader,
            nativeLibDirPath = nativeLibPrimaryDir,
            targetThemeResId = resolvedThemeRes,
            activityMetaData = activityMetaData,
            hostedActivityClassName = activityClass.name,
            archiveAppInfo = archiveInfo?.applicationInfo,
            axmlSummary = axml
        )

        val warnings = mutableListOf<String>()
        val previousTccl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = dexClassLoader

        val activityThreadObj = runCatching {
            findFieldInHierarchy(Activity::class.java, "mMainThread")?.get(hostActivity)
                ?: Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread")
                    .apply { isAccessible = true }
                    .invoke(null)
        }.getOrNull()
        val initialAppField = activityThreadObj?.let { findFieldInHierarchy(it.javaClass, "mInitialApplication") }
        val previousInitialApp = runCatching { initialAppField?.get(activityThreadObj) as? Application }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val allAppsList = runCatching {
            activityThreadObj?.let { findFieldInHierarchy(it.javaClass, "mAllApplications")?.get(it) as? MutableList<Application> }
        }.getOrNull()

        // Snapshot ActivityThread.mBoundApplication fields for reversible in-process identity
        val boundAppObj = runCatching {
            activityThreadObj?.let { findFieldInHierarchy(it.javaClass, "mBoundApplication")?.get(it) }
        }.getOrNull()
        val boundAppInfoField = boundAppObj?.let { findFieldInHierarchy(it.javaClass, "appInfo") }
        val previousBoundAppInfo = runCatching { boundAppInfoField?.get(boundAppObj) }.getOrNull()
        val boundProcessNameField = boundAppObj?.let { findFieldInHierarchy(it.javaClass, "processName") }
        val previousBoundProcessName = runCatching { boundProcessNameField?.get(boundAppObj) }.getOrNull()
        val boundInfoField = boundAppObj?.let { findFieldInHierarchy(it.javaClass, "info") }
        val previousBoundLoadedApk = runCatching { boundInfoField?.get(boundAppObj) }.getOrNull()

        // Snapshot ActivityThread.sPackageManager & basePm.mPM
        val sPackageManagerField = runCatching {
            activityThreadObj?.let { findFieldInHierarchy(it.javaClass, "sPackageManager") }
        }.getOrNull()
        val previousSPackageManager = runCatching { sPackageManagerField?.get(null) }.getOrNull()
        val basePmMpmField = runCatching {
            findFieldInHierarchy(hostActivity.packageManager.javaClass, "mPM")
        }.getOrNull()
        val previousBasePmMpm = runCatching { basePmMpmField?.get(hostActivity.packageManager) }.getOrNull()

        val windowInflaterField = hostActivity.window?.let { findFieldInHierarchy(it.javaClass, "mLayoutInflater") }
        val previousWindowInflater = runCatching {
            hostActivity.window?.let { windowInflaterField?.get(it) }
        }.getOrNull()
        val previousWindowCallback: Window.Callback? = hostActivity.window?.callback

        var targetApp: Application? = null
        var hostedActivityRef: Activity? = null
        var loadedLibsRef: List<String> = emptyList()
        var restoreStateNeeded = true
        fun restoreProcessHostBindings() {
            if (initialAppField != null && activityThreadObj != null && previousInitialApp != null) {
                runCatching { initialAppField.set(activityThreadObj, previousInitialApp) }
            }
            if (targetApp != null && allAppsList != null && targetApp !== previousInitialApp) {
                runCatching { allAppsList.remove(targetApp) }
            }
            if (boundAppObj != null) {
                if (boundAppInfoField != null && previousBoundAppInfo != null) {
                    runCatching { boundAppInfoField.set(boundAppObj, previousBoundAppInfo) }
                }
                if (boundProcessNameField != null && previousBoundProcessName != null) {
                    runCatching { boundProcessNameField.set(boundAppObj, previousBoundProcessName) }
                }
                if (boundInfoField != null && previousBoundLoadedApk != null) {
                    runCatching { boundInfoField.set(boundAppObj, previousBoundLoadedApk) }
                }
            }
            if (sPackageManagerField != null && previousSPackageManager != null) {
                runCatching { sPackageManagerField.set(null, previousSPackageManager) }
            }
            if (basePmMpmField != null && previousBasePmMpm != null) {
                runCatching { basePmMpmField.set(hostActivity.packageManager, previousBasePmMpm) }
            }
            if (windowInflaterField != null && hostActivity.window != null && previousWindowInflater != null) {
                runCatching { windowInflaterField.set(hostActivity.window, previousWindowInflater) }
            }
            if (hostActivity.window != null && previousWindowCallback != null) {
                runCatching { hostActivity.window.callback = previousWindowCallback }
            }
        }

        try {
            // Prepare isolated LoadedApk + ActivityThread identity + IPackageManager proxy BEFORE native load / Application init
            val minecraftLoadedApk = createAndConfigureMinecraftLoadedApk(
                activityThreadObj = activityThreadObj,
                sandboxContext = sandboxContext,
                pkg = pkg,
                dexClassLoader = dexClassLoader,
                resources = preparedAssets.resources,
                nativeLibDir = nativeLibPrimaryDir
            )
            if (boundAppObj != null) {
                runCatching { boundAppInfoField?.set(boundAppObj, sandboxContext.applicationInfo) }
                runCatching { boundProcessNameField?.set(boundAppObj, pkg.packageName) }
                if (minecraftLoadedApk != null) {
                    runCatching { boundInfoField?.set(boundAppObj, minecraftLoadedApk) }
                }
            }
            if (previousSPackageManager != null) {
                createIPackageManagerProxy(
                    originalPm = previousSPackageManager,
                    sandboxContext = sandboxContext,
                    targetPkg = pkg
                )?.let { pmProxy ->
                    runCatching { sPackageManagerField?.set(null, pmProxy) }
                    if (basePmMpmField != null && previousBasePmMpm === previousSPackageManager) {
                        runCatching { basePmMpmField.set(hostActivity.packageManager, pmProxy) }
                    }
                }
            }

            // 2. Preload bundled ARM64 ELF64 libs bound strictly to dexClassLoader BEFORE Application/Activity clinit
            val loadedLibs = preloadNativeLibrariesForClass(activityClass, libraries, logger)
            loadedLibsRef = loadedLibs

            // 3. Instantiate & attach isolated Application (custom APK Application or SandboxHostApplication)
            val createdApp = resolveOrCreateApplication(
                sandboxContext = sandboxContext,
                dexClassLoader = dexClassLoader,
                appClassName = resolvedAppClassName,
                activityThreadObj = activityThreadObj,
                initialAppField = initialAppField,
                allAppsList = allAppsList,
                minecraftLoadedApk = minecraftLoadedApk,
                warnings = warnings
            )
            targetApp = createdApp
            sandboxContext.attachApplicationContext(createdApp)

            // 4. Construct Activity instance (triggers MainActivity.<clinit> after Application & libs exist)
            val hostedActivity = activityClass.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance() as Activity
            hostedActivityRef = hostedActivity

            // Attach isolated ApkHostContextWrapper as base context of hosted Activity
            invokeMethodInHierarchy(
                target = hostedActivity,
                name = "attachBaseContext",
                paramTypes = arrayOf(Context::class.java),
                args = arrayOf(sandboxContext)
            ).onFailure { warnings.add("attachBaseContext: ${it.message}") }

            val hostedIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(pkg.packageName, activityClass.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Mirror Activity.attach(...) fields from hostActivity
            val fieldsToMirror = listOf(
                "mWindow",
                "mWindowManager",
                "mMainThread",
                "mInstrumentation",
                "mToken",
                "mEmbeddedID",
                "mUiThread",
                "mHandler",
                "mCurrentConfig"
            )
            for (fieldName in fieldsToMirror) {
                mirrorFieldFromHost(
                    host = hostActivity,
                    target = hostedActivity,
                    fieldName = fieldName,
                    warnings = warnings
                )
            }

            // Route host PhoneWindow LayoutInflater & Window.Callback to hosted Minecraft Activity
            if (windowInflaterField != null && hostActivity.window != null) {
                runCatching {
                    windowInflaterField.set(hostActivity.window, LayoutInflater.from(sandboxContext))
                }.onFailure { warnings.add("windowLayoutInflater: ${it.message}") }
            }
            runCatching {
                hostActivity.window?.callback = hostedActivity
            }
            setFieldStartingAt(hostedActivity, android.view.ContextThemeWrapper::class.java, "mInflater", null)

            // Keep mParent null so hostedActivity.isChild() == false like a normal top-level Activity
            setFieldStartingAt(hostedActivity, Activity::class.java, "mParent", null)
                .onFailure {
                    if (hostedActivity.isChild) warnings.add("mParent: ${it.message}")
                }
            setFieldStartingAt(hostedActivity, Activity::class.java, "mApplication", createdApp)
                .onFailure { warnings.add("mApplication: ${it.message}") }
            setFieldStartingAt(hostedActivity, android.view.ContextThemeWrapper::class.java, "mResources", preparedAssets.resources)
                .onFailure { warnings.add("mResources: ${it.message}") }
            if (resolvedThemeRes != 0) {
                setFieldStartingAt(hostedActivity, android.view.ContextThemeWrapper::class.java, "mThemeResource", resolvedThemeRes)
                runCatching { hostedActivity.setTheme(resolvedThemeRes) }
            }
            setFieldStartingAt(hostedActivity, android.view.ContextThemeWrapper::class.java, "mTheme", sandboxContext.theme)
                .onFailure {
                    if (resolvedThemeRes == 0) {
                        runCatching { hostedActivity.theme }
                    }
                }
            setFieldStartingAt(hostedActivity, Activity::class.java, "mIntent", hostedIntent)
                .onFailure { warnings.add("mIntent: ${it.message}") }
            setFieldStartingAt(hostedActivity, Activity::class.java, "mTitle", pkg.appLabel)
                .onFailure { warnings.add("mTitle: ${it.message}") }
            setFieldStartingAt(
                hostedActivity,
                Activity::class.java,
                "mComponent",
                ComponentName(pkg.packageName, activityClass.name)
            ).onFailure { warnings.add("mComponent: ${it.message}") }

            val populatedActivityInfo = if (matchedArchiveActivity != null) {
                ActivityInfo(matchedArchiveActivity).apply {
                    packageName = pkg.packageName
                    name = activityClass.name
                    applicationInfo = sandboxContext.applicationInfo
                    val mergedMeta = Bundle(matchedArchiveActivity.metaData ?: Bundle())
                    mergedMeta.putAll(activityMetaData)
                    metaData = mergedMeta
                    theme = resolvedThemeRes
                    exported = true
                }
            } else {
                ActivityInfo().apply {
                    packageName = pkg.packageName
                    name = activityClass.name
                    applicationInfo = sandboxContext.applicationInfo
                    metaData = Bundle(activityMetaData)
                    theme = resolvedThemeRes
                    exported = true
                }
            }
            setFieldStartingAt(hostedActivity, Activity::class.java, "mActivityInfo", populatedActivityInfo)
                .onFailure { warnings.add("mActivityInfo: ${it.message}") }

            lastDiagnosticSnapshot = buildHostDiagnosticSnapshot(
                pkg = pkg,
                activityClass = activityClass,
                hostedActivity = hostedActivity,
                dexClassLoader = dexClassLoader,
                appClassName = resolvedAppClassName,
                createdApp = createdApp,
                loadedLibs = loadedLibs,
                allApkLibs = libraries.map { it.fileName },
                archiveInfo = archiveInfo,
                mountedAssets = preparedAssets.mountedApkCount,
                warnings = warnings,
                includeDeepFailureProbe = false
            )
            if (warnings.isNotEmpty()) {
                logger?.warn("HOST_ACTIVITY", "Pre-onCreate host warnings: ${warnings.joinToString("; ")}")
            }
            logger?.info("HOST_DIAG", lastDiagnosticSnapshot)
            onBeforeActivityOnCreate(preparedAssets.mountedApkCount, loadedLibs.size, activityClass.name)

            val instrumentation = runCatching {
                findFieldInHierarchy(Activity::class.java, "mInstrumentation")?.get(hostActivity) as? Instrumentation
            }.getOrNull()

            if (instrumentation != null) {
                instrumentation.callActivityOnCreate(hostedActivity, null)
            } else {
                val onCreate = findMethodInHierarchy(activityClass, "onCreate", Bundle::class.java)
                    ?: throw NoSuchMethodException("onCreate(Bundle) not found on ${activityClass.name}")
                onCreate.isAccessible = true
                onCreate.invoke(hostedActivity, null)
            }

            restoreStateNeeded = false
            val handle = HostedActivityHandle(
                hostedActivity = hostedActivity,
                hostContext = sandboxContext,
                classLoader = dexClassLoader,
                activityClassName = activityClass.name,
                mountedAssetCount = preparedAssets.mountedApkCount,
                loadedNativeLibraries = loadedLibs,
                reflectionWarnings = warnings,
                onDestroyCleanup = { restoreProcessHostBindings() }
            )
            handle.dispatchStart()
            handle.dispatchResume()
            if (warnings.isNotEmpty()) {
                logger?.warn("HOST_ACTIVITY", "Reflection warnings: ${warnings.joinToString("; ")}")
            }
            logger?.success(
                "HOST_ACTIVITY",
                "Started ${activityClass.name} in-process (${loadedLibs.size} .so libs, ${preparedAssets.mountedApkCount} APK assets)"
            )
            return handle
        } catch (t: Throwable) {
            lastDiagnosticSnapshot = buildHostDiagnosticSnapshot(
                pkg = pkg,
                activityClass = activityClass,
                hostedActivity = hostedActivityRef,
                dexClassLoader = dexClassLoader,
                appClassName = resolvedAppClassName,
                createdApp = targetApp,
                loadedLibs = loadedLibsRef,
                allApkLibs = libraries.map { it.fileName },
                archiveInfo = archiveInfo,
                mountedAssets = preparedAssets.mountedApkCount,
                warnings = warnings,
                includeDeepFailureProbe = true
            )
            logger?.error("HOST_DIAG", lastDiagnosticSnapshot)
            throw t
        } finally {
            if (restoreStateNeeded) {
                restoreProcessHostBindings()
            }
            Thread.currentThread().contextClassLoader = previousTccl
        }
    }

    private fun createAndConfigureMinecraftLoadedApk(
        activityThreadObj: Any?,
        sandboxContext: ApkHostContextWrapper,
        pkg: InstalledMinecraftPackage,
        dexClassLoader: ClassLoader,
        resources: Resources,
        nativeLibDir: String
    ): Any? {
        if (activityThreadObj == null) return null
        return runCatching {
            val compatInfoClass = Class.forName("android.content.res.CompatibilityInfo")
            val defaultCompat = findFieldInHierarchy(compatInfoClass, "DEFAULT_COMPATIBILITY_INFO")?.get(null)
            val getPkgInfoNoCheck = findMethodInHierarchy(
                activityThreadObj.javaClass,
                "getPackageInfoNoCheck",
                ApplicationInfo::class.java,
                compatInfoClass
            ) ?: return null

            val loadedApk = getPkgInfoNoCheck.invoke(
                activityThreadObj,
                sandboxContext.applicationInfo,
                defaultCompat
            ) ?: return null

            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mApplicationInfo", sandboxContext.applicationInfo)
            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mAppDir", pkg.sourceDir)
            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mResDir", pkg.sourceDir)
            if (pkg.splitSourceDirs.isNotEmpty()) {
                val splits = pkg.splitSourceDirs.toTypedArray()
                setFieldStartingAt(loadedApk, loadedApk.javaClass, "mSplitAppDirs", splits)
                setFieldStartingAt(loadedApk, loadedApk.javaClass, "mSplitResDirs", splits)
            }
            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mDataDir", sandboxContext.dataDir.absolutePath)
            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mDataDirFile", sandboxContext.dataDir)
            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mLibDir", nativeLibDir)
            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mClassLoader", dexClassLoader)
            setFieldStartingAt(loadedApk, loadedApk.javaClass, "mResources", resources)
            loadedApk
        }.getOrNull()
    }

    internal fun createIPackageManagerProxy(
        originalPm: Any,
        sandboxContext: ApkHostContextWrapper,
        targetPkg: InstalledMinecraftPackage
    ): Any? {
        return runCatching {
            val ipmClass = Class.forName("android.content.pm.IPackageManager")
            if (!ipmClass.isInterface) return null
            if (Proxy.isProxyClass(originalPm.javaClass)) return originalPm
            val sandboxPm = sandboxContext.packageManager
            val targetUid = sandboxContext.applicationInfo.uid
            val targetName = targetPkg.packageName

            Proxy.newProxyInstance(ipmClass.classLoader, arrayOf(ipmClass)) { _, method, args ->
                val methodName = method.name
                val handled: Pair<Boolean, Any?> = when (methodName) {
                    "getPackageInfo" -> {
                        val pkgArg = args?.getOrNull(0) as? String
                        if (pkgArg == targetName) {
                            val flags = extractFlagsInt(args.getOrNull(1))
                            @Suppress("DEPRECATION")
                            true to sandboxPm.getPackageInfo(targetName, flags)
                        } else false to null
                    }
                    "getPackageInfoVersioned" -> {
                        val vp = args?.getOrNull(0) as? VersionedPackage
                        if (vp?.packageName == targetName) {
                            val flags = extractFlagsInt(args.getOrNull(1))
                            @Suppress("DEPRECATION")
                            true to sandboxPm.getPackageInfo(targetName, flags)
                        } else false to null
                    }
                    "getApplicationInfo" -> {
                        val pkgArg = args?.getOrNull(0) as? String
                        if (pkgArg == targetName) {
                            true to sandboxContext.applicationInfo
                        } else false to null
                    }
                    "getActivityInfo" -> {
                        val cn = args?.getOrNull(0) as? ComponentName
                        if (cn?.packageName == targetName) {
                            val flags = extractFlagsInt(args.getOrNull(1))
                            @Suppress("DEPRECATION")
                            true to sandboxPm.getActivityInfo(cn, flags)
                        } else false to null
                    }
                    "getReceiverInfo" -> {
                        val cn = args?.getOrNull(0) as? ComponentName
                        if (cn?.packageName == targetName) {
                            val flags = extractFlagsInt(args.getOrNull(1))
                            @Suppress("DEPRECATION")
                            true to sandboxPm.getReceiverInfo(cn, flags)
                        } else false to null
                    }
                    "getServiceInfo" -> {
                        val cn = args?.getOrNull(0) as? ComponentName
                        if (cn?.packageName == targetName) {
                            val flags = extractFlagsInt(args.getOrNull(1))
                            @Suppress("DEPRECATION")
                            true to sandboxPm.getServiceInfo(cn, flags)
                        } else false to null
                    }
                    "getProviderInfo" -> {
                        val cn = args?.getOrNull(0) as? ComponentName
                        if (cn?.packageName == targetName) {
                            val flags = extractFlagsInt(args.getOrNull(1))
                            @Suppress("DEPRECATION")
                            true to sandboxPm.getProviderInfo(cn, flags)
                        } else false to null
                    }
                    "getInstallerPackageName" -> {
                        if (args?.getOrNull(0) == targetName) {
                            true to "com.android.vending"
                        } else false to null
                    }
                    "getInstallSourceInfo" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && args?.getOrNull(0) == targetName) {
                            true to sandboxPm.getInstallSourceInfo(targetName)
                        } else false to null
                    }
                    "getPackagesForUid" -> {
                        val uidArg = (args?.getOrNull(0) as? Number)?.toInt()
                        if (uidArg == targetUid) {
                            val orig = invokeUnderlying(method, originalPm, args) as? Array<*>
                            val origStrings = orig?.filterIsInstance<String>().orEmpty()
                            true to (listOf(targetName) + origStrings).distinct().toTypedArray()
                        } else false to null
                    }
                    "getNameForUid" -> {
                        val uidArg = (args?.getOrNull(0) as? Number)?.toInt()
                        if (uidArg == targetUid) {
                            true to targetName
                        } else false to null
                    }
                    "checkPermission" -> {
                        val pkgArg = args?.getOrNull(1) as? String
                        if (pkgArg == targetName) {
                            true to PackageManager.PERMISSION_GRANTED
                        } else false to null
                    }
                    "checkSignatures" -> {
                        val p1 = args?.getOrNull(0) as? String
                        val p2 = args?.getOrNull(1) as? String
                        if (p1 == targetName && p2 == targetName) {
                            true to PackageManager.SIGNATURE_MATCH
                        } else false to null
                    }
                    else -> false to null
                }

                if (handled.first) {
                    handled.second
                } else {
                    invokeUnderlying(method, originalPm, args)
                }
            }
        }.getOrNull()
    }

    private fun extractFlagsInt(arg: Any?): Int {
        return when (arg) {
            is Number -> arg.toInt()
            else -> 0
        }
    }

    private fun invokeUnderlying(method: Method, target: Any, args: Array<out Any?>?): Any? {
        return try {
            if (args.isNullOrEmpty()) {
                method.invoke(target)
            } else {
                method.invoke(target, *args)
            }
        } catch (ite: InvocationTargetException) {
            throw ite.targetException ?: ite
        }
    }

    private fun resolveOrCreateApplication(
        sandboxContext: ApkHostContextWrapper,
        dexClassLoader: ClassLoader,
        appClassName: String?,
        activityThreadObj: Any?,
        initialAppField: Field?,
        allAppsList: MutableList<Application>?,
        minecraftLoadedApk: Any?,
        warnings: MutableList<String>
    ): Application {
        fun bindLoadedApkToApplication(app: Application) {
            if (minecraftLoadedApk != null) {
                setFieldStartingAt(app, Application::class.java, "mLoadedApk", minecraftLoadedApk)
                setFieldStartingAt(minecraftLoadedApk, minecraftLoadedApk.javaClass, "mApplication", app)
            }
        }

        if (!appClassName.isNullOrBlank()) {
            val customApp = runCatching {
                val cls = Class.forName(appClassName, true, dexClassLoader)
                if (Application::class.java.isAssignableFrom(cls)) {
                    val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Application
                    invokeMethodInHierarchy(
                        target = instance,
                        name = "attach",
                        paramTypes = arrayOf(Context::class.java),
                        args = arrayOf(sandboxContext)
                    ).recoverCatching {
                        invokeMethodInHierarchy(
                            target = instance,
                            name = "attachBaseContext",
                            paramTypes = arrayOf(Context::class.java),
                            args = arrayOf(sandboxContext)
                        ).getOrThrow()
                    }
                    bindLoadedApkToApplication(instance)
                    sandboxContext.attachApplicationContext(instance)
                    if (initialAppField != null && activityThreadObj != null) {
                        runCatching { initialAppField.set(activityThreadObj, instance) }
                    }
                    if (allAppsList != null && instance !in allAppsList) {
                        runCatching { allAppsList.add(instance) }
                    }
                    instance.onCreate()
                    instance
                } else null
            }.onFailure { err ->
                val root = generateSequence(err) { it.cause }.lastOrNull() ?: err
                warnings.add("Custom Application ($appClassName) skipped: ${root.javaClass.simpleName}: ${root.message}")
            }.getOrNull()
            if (customApp != null) return customApp
        }

        val fallbackApp = SandboxHostApplication(sandboxContext)
        bindLoadedApkToApplication(fallbackApp)
        sandboxContext.attachApplicationContext(fallbackApp)
        if (initialAppField != null && activityThreadObj != null) {
            runCatching { initialAppField.set(activityThreadObj, fallbackApp) }
        }
        if (allAppsList != null && fallbackApp !in allAppsList) {
            runCatching { allAppsList.add(fallbackApp) }
        }
        return fallbackApp
    }

    private fun buildHostDiagnosticSnapshot(
        pkg: InstalledMinecraftPackage,
        activityClass: Class<*>,
        hostedActivity: Activity?,
        dexClassLoader: ClassLoader,
        appClassName: String?,
        createdApp: Application?,
        loadedLibs: List<String>,
        allApkLibs: List<String>,
        archiveInfo: PackageInfo?,
        mountedAssets: Int,
        warnings: List<String>,
        includeDeepFailureProbe: Boolean
    ): String {
        val supers = buildList {
            var c: Class<*>? = activityClass
            while (c != null && size < 8) {
                add(c.name)
                c = c.superclass
            }
        }.joinToString(" -> ")
        val mainThreadAttached = hostedActivity?.let { act ->
            runCatching { findFieldInHierarchy(Activity::class.java, "mMainThread")?.get(act) != null }.getOrNull()
        }
        val activeLoaders = listOfNotNull(
            hostedActivity?.javaClass?.classLoader,
            Thread.currentThread().contextClassLoader,
            dexClassLoader
        ).distinct()
        var mcpelifeCls: Class<*>? = null
        var mcpelifeErr: Throwable? = null
        for (cl in activeLoaders) {
            try {
                mcpelifeCls = Class.forName("${pkg.packageName}.mcpelife", false, cl)
                break
            } catch (t: Throwable) {
                mcpelifeErr = t
            }
        }
        val mcpelifeSummary = if (mcpelifeCls != null) {
            val intArrFields = mcpelifeCls.declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == IntArray::class.java }
                .mapNotNull { f ->
                    runCatching {
                        f.isAccessible = true
                        val arr = f.get(null) as? IntArray
                        "${f.name}[${arr?.size ?: -1}]"
                    }.getOrNull()
                }
            "mcpelife(cl=${mcpelifeCls.classLoader?.javaClass?.simpleName}, fields=${mcpelifeCls.declaredFields.size}, methods=${mcpelifeCls.declaredMethods.size}, staticIntArr=${intArrFields.joinToString()})"
        } else {
            val root = mcpelifeErr?.let { err -> generateSequence(err) { it.cause }.lastOrNull() ?: err }
            "mcpelife=${root?.javaClass?.simpleName ?: "None"}(${root?.message?.take(140) ?: ""})"
        }
        val deepFailureExtras = if (includeDeepFailureProbe) {
            val dexHits = runCatching {
                DexInspector.scanClassesByTerms(
                    apkPaths = collectReadableApkPaths(pkg),
                    terms = listOf("mcpelife", "pairip"),
                    maxResults = 16
                )
            }.getOrDefault(emptyList())
            val dexElementsCount = runCatching {
                val pathList = findFieldInHierarchy(dexClassLoader.javaClass, "pathList")?.get(dexClassLoader)
                val elements = pathList?.let { findFieldInHierarchy(it.javaClass, "dexElements")?.get(it) as? Array<*> }
                elements?.size ?: -1
            }.getOrDefault(-1)
            val procMaps = runCatching {
                File("/proc/self/maps").bufferedReader().useLines { lines ->
                    lines.filter { l ->
                        "libMCPELIFE" in l || "libpairip" in l || "libminecraftpe" in l || "mc_vault" in l
                    }.map { it.substringAfterLast(' ') }
                        .distinct()
                        .take(12)
                        .toList()
                }
            }.getOrDefault(emptyList())
            ", dexElems=$dexElementsCount, dexHits=${dexHits.joinToString(",")}, maps=${procMaps.joinToString(",")}"
        } else {
            ""
        }
        val appDesc = createdApp?.javaClass?.name ?: "null"
        val warnDesc = if (warnings.isEmpty()) "none" else warnings.joinToString(" | ")
        val archSummary = "archiveOk=${archiveInfo != null},perms=${archiveInfo?.requestedPermissions?.size ?: -1}"
        val soSummary = "loaded=${loadedLibs.size}/all=${allApkLibs.size}[${allApkLibs.joinToString(",")}]"
        return "diag[pkg=${pkg.packageName} v${pkg.versionName}(${pkg.versionCode}), appManifest=${appClassName ?: "none"}, appActive=$appDesc, mMainThreadOk=$mainThreadAttached, assets=$mountedAssets, $soSummary, $archSummary, chain=$supers, $mcpelifeSummary$deepFailureExtras, warnings=$warnDesc]"
    }

    fun collectReadableApkPaths(pkg: InstalledMinecraftPackage): List<String> {
        return buildList {
            if (pkg.sourceDir.isNotBlank()) add(pkg.sourceDir)
            addAll(pkg.splitSourceDirs.filter { it.isNotBlank() })
        }.distinct().filter { path ->
            val f = File(path)
            f.isFile && f.canRead()
        }
    }

    private fun buildProfileKey(pkg: InstalledMinecraftPackage): String {
        val raw = pkg.vaultId?.takeIf { it.isNotBlank() }
            ?: "${pkg.packageName}_${pkg.versionName}_${pkg.versionCode}"
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun findFieldInHierarchy(cls: Class<*>, fieldName: String): Field? {
        var cur: Class<*>? = cls
        while (cur != null) {
            val f = runCatching {
                cur.getDeclaredField(fieldName).apply { isAccessible = true }
            }.getOrNull()
            if (f != null) return f
            cur = cur.superclass
        }
        return null
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var cur: Class<*>? = cls
        while (cur != null) {
            val m = runCatching {
                cur.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }
            }.getOrNull()
            if (m != null) return m
            cur = cur.superclass
        }
        return null
    }

    private fun invokeMethodInHierarchy(
        target: Any,
        name: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Result<Any?> = runCatching {
        val m = findMethodInHierarchy(target.javaClass, name, *paramTypes)
            ?: throw NoSuchMethodException("$name not found on ${target.javaClass.name}")
        m.invoke(target, *args)
    }

    internal fun mirrorFieldFromHost(
        host: Activity,
        target: Activity,
        fieldName: String,
        warnings: MutableList<String>
    ) {
        runCatching {
            val f = findFieldInHierarchy(Activity::class.java, fieldName)
                ?: findFieldInHierarchy(host.javaClass, fieldName)
                ?: return@runCatching
            val v = f.get(host)
            if (f.declaringClass.isAssignableFrom(target.javaClass)) {
                f.set(target, v)
            } else {
                setFieldStartingAt(target, Activity::class.java, fieldName, v).getOrThrow()
            }
        }.onFailure { err ->
            warnings.add("$fieldName: ${err.javaClass.simpleName}: ${err.message}")
        }
    }

    private fun setFieldStartingAt(
        target: Any,
        startClass: Class<*>,
        fieldName: String,
        value: Any?
    ): Result<Unit> = runCatching {
        val f = findFieldInHierarchy(startClass, fieldName)
            ?: throw NoSuchFieldException("$fieldName not found on ${startClass.name}")
        f.set(target, value)
    }

    private fun setFieldInHierarchy(target: Any, fieldName: String, value: Any?): Result<Unit> =
        setFieldStartingAt(target, target.javaClass, fieldName, value)
}
