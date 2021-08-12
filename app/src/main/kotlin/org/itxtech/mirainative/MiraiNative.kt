/*
 *
 * Mirai Native
 *
 * Copyright (C) 2020-2021 iTX Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * @author PeratX
 * @website https://github.com/iTXTech/mirai-native
 *
 */

package org.itxtech.mirainative

import android.os.Process
import android.os.Build
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.extension.PluginComponentStorage
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescriptionBuilder
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.MiraiConsole
import org.itxtech.mirainative.manager.CacheManager
import org.itxtech.mirainative.manager.EventManager
import org.itxtech.mirainative.manager.LibraryManager
import org.itxtech.mirainative.manager.PluginManager
import org.itxtech.mirainative.util.ConfigMan
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

object MiraiNative : KotlinPlugin(
    JvmPluginDescriptionBuilder("MiraiNative", "2.0.2-cp-android")
        .id("org.itxtech.mirainative")
        .author("iTX Technologies & 溯洄")
        .info("强大的 mirai 原生插件加载器。")
        .build()
) {
    private val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private val randomPath: String = (1..10)
            .map { i -> kotlin.random.Random.nextInt(0, charPool.size) }
            .map(charPool::get)
            .joinToString("");
    private val tmp: File by lazy { File((System.getProperty("java.io.tmpdir") ?: "") + File.separatorChar + randomPath).also{ it.mkdirs() } }
    private val lib: File by lazy { File(tmp.absolutePath + File.separatorChar + "libraries").also { it.mkdirs() } }
    private val dll: File by lazy { File(tmp.absolutePath + File.separatorChar + "CQP.dll") }
    val pl: File by lazy { File(tmp.absolutePath  + File.separatorChar + "plugins").also { it.mkdirs() } }
    val plArc: File by lazy { File(pl.absolutePath + File.separatorChar + systemName + File.separatorChar + systemArch).also { it.mkdirs() } }
    private val Plib: File by lazy { File(dataFolder.absolutePath + File.separatorChar + "libraries").also { it.mkdirs() } }
    private val Pdll: File by lazy { File(dataFolder.absolutePath + File.separatorChar + "CQP.dll") }
    private val Ppl: File by lazy { File(dataFolder.absolutePath + File.separatorChar + "plugins").also { it.mkdirs() } }
    private val Pchecksum: File by lazy { File(dataFolder.absolutePath + File.separatorChar + ".mninstallchecksum") }
    val imageDataPath: File by lazy { File(dataFolder.absolutePath + File.separatorChar + ".." + File.separatorChar + "image").also { it.mkdirs() } }
    val recDataPath: File by lazy { File(dataFolder.absolutePath + File.separatorChar + ".." + File.separatorChar + "record").also { it.mkdirs() } }
    val systemName: String by lazy {
        val name = "android"
        logger.info("当前系统: $name")
        "android"
    }

    val systemArch: String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Process.is64Bit()) {
                val arch = Build.SUPPORTED_64_BIT_ABIS[0];
                logger.info("当前架构: $arch")
                when (arch) {
                    "arm64-v8a" -> "aarch64"
                    "x86_64" -> "amd64"
                    else -> arch
                }
            } else {
                val arch = Build.SUPPORTED_32_BIT_ABIS[0];
                logger.info("当前架构: $arch")
                when (arch) {
                    "armeabi-v7a" -> "arm"
                    "armeabi" -> "arm"
                    "x86" -> "i386"
                    else -> arch
                }
            }
        } else {
            val arch = Build.SUPPORTED_ABIS[0];
            logger.info("当前架构: $arch")
            when (arch) {
                "armeabi-v7a" -> "arm"
                "armeabi" -> "arm"
                "x86" -> "i386"
                "arm64-v8a" -> "aarch64"
                "x86_64" -> "amd64"
                else -> arch
            }
        }
    }


        @OptIn(ExperimentalCoroutinesApi::class)
        private val dispatcher = newSingleThreadContext("MiraiNative Main") + SupervisorJob()

        @OptIn(ExperimentalCoroutinesApi::class)
        val menuDispatcher = newSingleThreadContext("MiraiNative Menu")

        @OptIn(ObsoleteCoroutinesApi::class)
        val eventDispatcher =
            newFixedThreadPoolContext(when {
                Runtime.getRuntime().availableProcessors() == 0 -> 4
                else -> Runtime.getRuntime().availableProcessors()
            } * 2, "MiraiNative Events")

        var botOnline = false
        val bot: Bot by lazy { Bot.instances.first() }

        private fun ByteArray.checksum() = BigInteger(1, MessageDigest.getInstance("MD5").digest(this))

        private fun checkNativeLibs() {
            logger.info("正在加载 Mirai Native Bridge ${dll.absolutePath}")
            LibraryManager.load(dll.absolutePath)

            lib.listFiles()?.forEach { file ->
                if (file.absolutePath.endsWith(".dll")) {
                    logger.info("正在加载外部库 " + file.absolutePath)
                    LibraryManager.load(file.absolutePath)
                }
            }
        }

        fun setBotOnline() {
            if (!botOnline) {
                botOnline = true
                nativeLaunch {
                    ConfigMan.init()
                    logger.info("Mirai Native 正启用所有插件。")
                    PluginManager.enablePlugins()
                }
            }
        }

        fun unzipToRootPath(UpdateZip: InputStream) {
            ZipInputStream(UpdateZip).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val destPath = File(MiraiConsole.rootPath.toAbsolutePath().toString() +
                            File.separatorChar + entry.name)
                    if (entry.isDirectory()) {
                        destPath.mkdirs()
                    } else {
                        val parPath = destPath.getParent()
                        if (parPath != null) {
                            File(parPath).mkdirs()
                        }
                        destPath.outputStream().use { output ->
                            zip.copyTo(output)
                        }
                    }
                }
            }
        }

        override fun PluginComponentStorage.onLoad() {
            var InstallZip = getResourceAsStream("mn-install.zip")
            if (InstallZip != null) {
                InstallZip.use { zip ->
                    val check = zip.readBytes().checksum().toString()
                    if (!Pchecksum.exists() || Pchecksum.readText() != check) {
                        getResourceAsStream("mn-install.zip")!!.use { zip1 ->
                            unzipToRootPath(zip1)
                        }
                        Pchecksum.writeText(check)
                    }
                }
            }

            val UpdateZip = File(MiraiConsole.rootPath.toAbsolutePath().toString() + File.separatorChar + "mn-install.zip")
            if (UpdateZip.exists() && !UpdateZip.isDirectory()) {
                UpdateZip.inputStream().use { zip ->
                    unzipToRootPath(zip)
                }
                UpdateZip.delete()
            }

            var nativeLib : InputStream
            try {
                nativeLib = getResourceAsStream("CQP.$systemName.$systemArch.dll")!!
            } catch(e : NullPointerException) {
                logger.warning("当前运行时环境可能不与 Mirai Native 兼容。")
                logger.warning("如果您正在开发或调试其他环境下的 Mirai Native，请忽略此警告。")
                nativeLib = getResourceAsStream("CQP.android.aarch64.dll")!!
            }

            val libData = nativeLib.readBytes()
            nativeLib.close()

            if (!Pdll.exists()) {
                logger.info("找不到 ${Pdll.absolutePath}，写出自带的 CQP.dll。")
                Pdll.writeBytes(libData)
            } else if (libData.checksum() != Pdll.readBytes().checksum()) {
                logger.warning("${Pdll.absolutePath} 与 Mirai Native 内置的 CQP.dll 的校验和不同。已用内置版本替换。")
                Pdll.writeBytes(libData)
            }

            copyPlugins()
            initDataDir()
        }

        private fun copyPlugins() {
            Pdll.copyTo(dll)
            Plib.copyRecursively(lib)
            Ppl.copyRecursively(pl)
        }

        private fun File.mkdirsOrExists() = if (exists()) true else mkdirs()

        private fun initDataDir() {
            if (!imageDataPath.mkdirsOrExists() || !recDataPath.mkdirsOrExists()) {
                logger.warning("图片或语音文件夹创建失败，可能没有使用管理员权限运行。位置：$imageDataPath 与 $recDataPath")
            }
            File(imageDataPath, "MIRAI_NATIVE_IMAGE_DATA").createNewFile()
            File(recDataPath, "MIRAI_NATIVE_RECORD_DATA").createNewFile()
        }

        @OptIn(InternalAPI::class)
        fun getDataFile(type: String, name: String): InputStream? {
            if (name.startsWith("base64://")) {
                return ByteArrayInputStream(name.split("base64://", limit = 2)[1].decodeBase64Bytes())
            }
            arrayOf(
                "data" + File.separatorChar + type + File.separatorChar,
                dataFolder.absolutePath + File.separatorChar + ".." + File.separatorChar + type + File.separatorChar,
                (System.getProperty("java.home") ?: "") + File.separatorChar + "bin" + File.separatorChar + type + File.separatorChar,
                (System.getProperty("java.home") ?: "") + File.separatorChar + type + File.separatorChar,
                ""
            ).forEach {
                val f = File(it + name).absoluteFile
                if (f.exists()) {
                    return f.inputStream()
                }
            }
            return null
        }

        private suspend fun CoroutineScope.processMessage() {
            while (isActive) {
                Bridge.processMessage()
                delay(10)
            }
        }

        override fun onEnable() {

            checkNativeLibs()
            PluginManager.loadPlugins()

            nativeLaunch { processMessage() }
            launch(menuDispatcher) { processMessage() }

            PluginManager.registerCommands()
            EventManager.registerEvents()

            if (Bot.instances.isNotEmpty() && Bot.instances.first().isOnline) {
                setBotOnline()
            }

            launch {
                while (isActive) {
                    CacheManager.checkCacheLimit(ConfigMan.config.cacheExpiration)
                    delay(60000L) //1min
                }
            }
        }

        override fun onDisable() {
            ConfigMan.save()
            CacheManager.clear()
            runBlocking {
                PluginManager.unloadPlugins().join()
                nativeLaunch { Bridge.shutdown() }.join()
                dispatcher.cancel()
                dispatcher[Job]?.join()
            }
            tmp.deleteRecursively()
        }

        fun nativeLaunch(b: suspend CoroutineScope.() -> Unit) = launch(context = dispatcher, block = b)

        fun launchEvent(b: suspend CoroutineScope.() -> Unit) = launch(context = eventDispatcher, block = b)

        fun getVersion(): String {
            var version = description.version.toString()
            val mf = javaClass.classLoader?.getResources("META-INF/MANIFEST.MF")
            while (mf != null && mf.hasMoreElements()) {
                val manifest = Manifest(mf.nextElement().openStream())
                if ("iTXTech MiraiNative" == manifest.mainAttributes.getValue("Name")) {
                    version += "-" + manifest.mainAttributes.getValue("Revision")
                }
            }
            return version
        }
    }

