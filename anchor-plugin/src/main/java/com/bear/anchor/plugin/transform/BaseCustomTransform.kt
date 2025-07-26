package com.bear.anchor.plugin.transform

import com.android.SdkConstants
import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Status
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.builder.utils.isValidZipEntryName
import com.android.utils.FileUtils
import com.google.common.io.Files
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 自定义 Transform 模版
 */
abstract class BaseCustomTransform(private val enableLog: Boolean = true) : Transform() {

    //线程池，可提升 80% 的执行速度
    private var waitableExecutor: WaitableExecutor = WaitableExecutor.useGlobalSharedThreadPool()

    /**
     * 此方法提供给上层进行字节码插桩
     */
    abstract fun provideFunction(): ((InputStream, OutputStream) -> Unit)?

    /**
     * 上层可重写该方法进行文件过滤
     */
    open fun classFilter(className: String) = className.endsWith(SdkConstants.DOT_CLASS)

    /**
     * 项目中会有各种各样格式的文件，该方法可以设置 Transform 接收的文件类型
     * 具体取值范围：
     * CONTENT_CLASS：Java 字节码文件，
     * CONTENT_JARS：jar 包
     * CONTENT_RESOURCES：资源，包含 java 文件
     * CONTENT_DEX：dex 文件
     * CONTENT_DEX_WITH_RESOURCES：包含资源的 dex 文件
     *
     * 我们能用的就两种：CONTENT_CLASS 和 CONTENT_JARS
     * 其余几种仅 AGP 可用
     *
     * 默认：获取输入的字节码文件
     */
    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    /**
     * 默认：检索整个项目的内容
     */
    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    /**
     * 表示当前 Transform 是否支持增量编译 true：支持 false：不支持
     * 默认开启增量编译
     */
    override fun isIncremental(): Boolean {
        return true
    }

    /**
     * 对输入的数据做检索操作：
     * 1.处理增量编译
     * 2.处理并发逻辑
     */
    override fun transform(transformInvocation: TransformInvocation) {
        printWelcomeLog()
        log("Transform start...")
        val transformStart = System.currentTimeMillis()
        super.transform(transformInvocation)
        // 输入内容
        val inputs = transformInvocation.inputs
        // 输出内容
        val outputProvider = transformInvocation.outputProvider
        // 1.子类实现字节码插桩操作
        val function = provideFunction()
        // 2.不是增量编译，删除所有旧的输出内容
        if (!transformInvocation.isIncremental) {
            outputProvider.deleteAll()
        }
        for (input in inputs) {
            // 3.Jar 包处理
            handleInputJar(input, outputProvider, transformInvocation.isIncremental)
            // 4.文件夹处理
            handleInputDir(input, outputProvider, transformInvocation.isIncremental)
        }
        waitableExecutor.waitForTasksWithQuickFail<Any>(true)
        log("Transform end...")
        log("Transform cost ${(System.currentTimeMillis() - transformStart) / 1000}ms")
    }

    private fun handleInputJar(
        input: TransformInput,
        outputProvider: TransformOutputProvider,
        isIncremental: Boolean
    ) {
        log("Transform jarInputs start. isIncremental = $isIncremental")
        val function = provideFunction()
        input.jarInputs.forEach { jarInput ->
            val jarInputFile = jarInput.file
            val jarOutputFile = outputProvider.getContentLocation(
                jarInput.name,
                jarInput.contentTypes,
                jarInput.scopes,
                Format.JAR
            )
            if (isIncremental) {
                // 3.1.增量编译中处理 Jar 包逻辑
                when (jarInput.status) {
                    Status.NOTCHANGED -> {
                        // Do nothing.
                    }

                    Status.ADDED, Status.CHANGED -> {
                        waitableExecutor.execute {
                            doTransformJar(jarInputFile, jarOutputFile, function)
                        }
                    }

                    Status.REMOVED -> {
                        // Delete
                        FileUtils.delete(jarOutputFile)
                    }
                }
            } else {
                waitableExecutor.execute {
                    doTransformJar(jarInputFile, jarOutputFile, function)
                }
            }
        }
    }

    private fun handleInputDir(
        input: TransformInput,
        outputProvider: TransformOutputProvider,
        isIncremental: Boolean
    ) {
        log("Transform dirInput start. isIncremental = $isIncremental")
        val function = provideFunction()
        input.directoryInputs.forEach { directoryInput ->
            val inputDirFile = directoryInput.file
            val outputDir = outputProvider.getContentLocation(
                directoryInput.name,
                directoryInput.contentTypes,
                directoryInput.scopes,
                Format.DIRECTORY
            )
            if (isIncremental) {
                // 4.1.增量编译中处理文件夹逻辑
                for ((inputFile, status) in directoryInput.changedFiles) {
                    val outputFile = concatOutputFilePath(outputDir, inputFile)
                    when (status) {
                        Status.NOTCHANGED -> {
                            // Do nothing.
                        }

                        Status.ADDED, Status.CHANGED -> {
                            waitableExecutor.execute {
                                doTransformFile(inputFile, outputFile, function)
                            }
                        }

                        Status.REMOVED -> {
                            // Delete
                            FileUtils.delete(outputFile)
                        }
                    }
                }
            } else {
                // 4.2. 非增量编译中处理文件夹逻辑
                FileUtils.getAllFiles(inputDirFile).forEach { inputFile ->
                    waitableExecutor.execute {
                        val outputFile = concatOutputFilePath(outputDir, inputFile)
                        doTransformFile(inputFile, outputFile, function)
                    }
                }
            }
        }
    }

    /**
     * Do transform Jar.
     */
    private fun doTransformJar(
        inputJar: File,
        outputJar: File,
        function: ((InputStream, OutputStream) -> Unit)?
    ) {
        // Create parent directories to hold outputJar file.
        Files.createParentDirs(outputJar)
        // Unzip.
        FileInputStream(inputJar).use { fis ->
            ZipInputStream(fis).use { zis ->
                // Zip
                FileOutputStream(outputJar).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        var entry = zis.nextEntry
                        while (entry != null && isValidZipEntryName(entry)) {
                            if (!entry.isDirectory) {
                                zos.putNextEntry(ZipEntry(entry.name))
                                if (classFilter(entry.name)) {
                                    // Apply transform function.
                                    applyFunction(zis, zos, function)
                                } else {
                                    // Copy
                                    zis.copyTo(zos)
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        }
    }

    /**
     * Do transform file.
     */
    private fun doTransformFile(
        inputFile: File,
        outputFile: File,
        function: ((InputStream, OutputStream) -> Unit)?
    ) {
        // Create parent directories to hold outputFile file.
        Files.createParentDirs(outputFile)
        if (classFilter(inputFile.name)) {
            FileInputStream(inputFile).use { fis ->
                FileOutputStream(outputFile).use { fos ->
                    // Apply transform function.
                    applyFunction(fis, fos, function)
                }
            }
        } else {
            // Copy.
            FileUtils.copyFile(inputFile, outputFile)
        }
    }

    private fun applyFunction(
        input: InputStream,
        output: OutputStream,
        function: ((InputStream, OutputStream) -> Unit)?
    ) {
        try {
            if (function != null) {
                function.invoke(input, output)
            } else {
                // 出现异常执行Copy，保证transform输出文件有内容
                input.copyTo(output)
            }
        } catch (e: Exception) {
            throw e.cause!!
        }
    }

    /**
     * 创建输出的文件
     */
    private fun concatOutputFilePath(outputDir: File, inputFile: File) =
        File(outputDir, inputFile.name)

    /**
     * log 打印
     */
    private fun log(logStr: String) {
        if (enableLog) {
            println("$name - $logStr")
        }
    }

    /**
     * 打印一段 welcome log 日志
     */
    private fun printWelcomeLog() {
        println()
        println("******************************************************************************")
        println("******                                                                  ******")
        println("******                欢迎使用 $name 编译插件                    ******")
        println("******                                                                  ******")
        println("******************************************************************************")
        println()
    }
}