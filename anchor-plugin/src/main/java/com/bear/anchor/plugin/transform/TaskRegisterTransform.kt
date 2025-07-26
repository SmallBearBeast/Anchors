package com.bear.anchor.plugin.transform

import com.android.build.api.variant.VariantInfo
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class TaskRegisterTransform : BaseCustomTransform(true) {

    override fun getName(): String {
        return "TaskRegisterTransform"
    }

    override fun classFilter(className: String): Boolean {
        return className.endsWith("TaskRegister.class")
    }

    override fun applyToVariant(variant: VariantInfo?): Boolean {
        return true
    }

    // 增量编译有问题，暂时关闭
    override fun isIncremental(): Boolean {
        return false
    }

    override fun provideFunction(): (InputStream, OutputStream) -> Unit {
        return { inputStream, outputStream ->
            // 使用 input 输入流构建 ClassReader
            val reader = ClassReader(inputStream)
            // 使用 ClassReader 和 flags 构建 ClassWriter
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            // 使用 ClassWriter 构建我们自定义的 ClassVisitor
//            val registryClassNames = findRegistryClassNames(File("app/build/generated/source/kapt/debug/com/bear/anchors/registry"))
            val registryClassNames = findAllRegistryClassNames()
            val visitor = TaskRegisterClassVisitor(writer, registryClassNames)
            // 最后通过 ClassReader 的 accept 将每一条字节码指令传递给 ClassVisitor
            reader.accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            // 将修改后的字节码文件转换成字节数组，最后通过输出流修改文件，这样就实现了字节码的插桩
            outputStream.write(writer.toByteArray())
        }
    }

    private fun findRegistryClassNames(genDir: File): List<String> {
        return genDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Registry.kt") }
            .map {
                // 转为全限定类名
                val relPath = it.relativeTo(genDir).invariantSeparatorsPath
                "com.bear.anchors.registry." + relPath.removeSuffix(".kt")
            }
            .toList()
    }

    private fun findAllRegistryClassNames(): List<String> {
        val rootDir = File(System.getProperty("user.dir"))
        val registryClassNames = mutableListOf<String>()
        // 遍历所有 module
        rootDir.listFiles()?.forEach { moduleDir ->
            val kaptDir = File(moduleDir, "build/generated/source/kapt")
            if (kaptDir.exists()) {
                // 遍历所有 variant（如 debug/release）
                kaptDir.listFiles()?.forEach { variantDir ->
                    // 递归查找所有 Registry.kt 文件
                    variantDir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith("Registry.kt") }
                        .forEach { file ->
                            // 这里需要根据实际包名拼接全限定类名
                            // 假设包名为 com.bear.anchors.registry
                            val relPath = file.relativeTo(variantDir).invariantSeparatorsPath
                            val className = relPath.removeSuffix(".kt").replace('/', '.')
                            registryClassNames.add(className)
                        }
                }
            }
        }
        return registryClassNames
    }
}

class TaskRegisterClassVisitor(visitor: ClassVisitor, private val registryClassNames: List<String>) : ClassVisitor(Opcodes.ASM9, visitor) {
    override fun visitMethod(
        access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, desc, signature, exceptions)
        if (name == "register") {
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitCode() {
                    super.visitCode()
                    // 在方法开头插入注册代码
                    for (className in registryClassNames) {
                        println("register $className")
                        val internalName = className.replace('.', '/')
                        mv.visitTypeInsn(Opcodes.NEW, internalName)
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false)
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, "register", "()V", false)
                    }
                }
            }
        }
        return mv
    }
}