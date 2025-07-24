package com.bear.buildsrc.transform

import com.android.build.api.transform.QualifiedContent
import com.android.build.gradle.internal.pipeline.TransformManager
import java.io.InputStream
import java.io.OutputStream

class CustomTransform : BaseCustomTransform(true) {
    /**
     * 设置我们自定义 Transform 对应的 Task 名称，Gradle 在编译的时候，会将这个名称经过一些拼接显示在控制台上
     */
    override fun getName(): String {
        return "CustomTransform"
    }

    /**
     * 此方法可以使用 ASM 或 Javassist 进行字节码插桩
     * 目前只是一个默认实现
     */
    override fun provideFunction(): (InputStream, OutputStream) -> Unit {
        return { inputStream, outputStream ->
            inputStream.copyTo(outputStream)
        }
    }

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }
}