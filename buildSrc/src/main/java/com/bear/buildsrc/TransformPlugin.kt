package com.bear.buildsrc

import com.android.build.gradle.AppExtension
import com.bear.buildsrc.transform.CustomTransform
import org.gradle.api.Plugin
import org.gradle.api.Project

class TransformPlugin: Plugin<Project>{
    override fun apply(project: Project) {
        println("Hello TransformPlugin")
        // 1、获取 Android 扩展
        val androidExtension = project.extensions.getByType(AppExtension::class.java)
        // 2、注册 Transform
        androidExtension.registerTransform(CustomTransform())
//        androidExtension.registerTransform(CostTimeTransform())
    }
}