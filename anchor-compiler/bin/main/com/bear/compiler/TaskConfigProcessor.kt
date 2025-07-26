package com.bear.compiler

import com.bear.annotation.TaskConfig
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

class TaskConfigProcessor : AbstractProcessor() {

    override fun getSupportedAnnotationTypes(): Set<String> {
        return setOf(TaskConfig::class.java.canonicalName)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latestSupported()
    }

    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        roundEnv.getElementsAnnotatedWith(TaskConfig::class.java)
            .filter { it.kind == ElementKind.CLASS }
            .forEach { element ->
                try {
                    val typeElement = element as TypeElement
                    val annotation = typeElement.getAnnotation(TaskConfig::class.java)
                    generateTaskRegistry(typeElement, annotation)
                } catch (e: Exception) {
                    processingEnv.messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Error processing @TaskConfig: ${e.message}"
                    )
                }
            }
        return true
    }

    private fun generateTaskRegistry(element: TypeElement, config: TaskConfig) {
        val className = element.simpleName.toString()
        val packageName = "com.bear.anchors.registry"

        val dependencies = config.dependencies.joinToString(separator = ",")
        val funStatement = if (dependencies.isEmpty()) {
            """
                // 打印注册信息
                println("Registered task: $className with " +
                    "dependencies=${config.dependencies.contentToString()}, " +
                    "priority=${config.priority}, " +
                    "description=${config.description}")
            """.trimIndent()
        } else {
            """
                // 添加子任务
                "$dependencies".split(",").forEach { 
                    it.sons("${config.name}")
                }               
            
                // 打印注册信息
                println("Registered task: $className with " +
                    "dependencies=${config.dependencies.contentToString()}, " +
                    "priority=${config.priority}, " +
                    "description=${config.description}")
                """.trimIndent()
        }
        // 1. 生成 register() 方法实现
        val registerFun = FunSpec.builder("register")
            .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
            .addStatement(funStatement)
            .build()

        // 2. 生成注册类（实现 ITaskRegister 接口）
        val registryClass = TypeSpec.classBuilder("${className}Registry")
            .addModifiers(KModifier.PUBLIC)
            .addSuperinterface(ClassName("com.effective.android.anchors.register", "ITaskRegister"))
            .addFunction(registerFun)
            .build()

        // 3. 生成文件
        FileSpec.builder(packageName, "${className}Registry")
            .addImport("com.effective.android.anchors", "sons") // 关键：导入扩展函数
            .addType(registryClass)
            .addFileComment("Generated code from @TaskConfig. Do not edit!")
            .build()
            .writeTo(processingEnv.filer)
    }
}