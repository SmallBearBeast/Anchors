package com.effective.android.sample

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.effective.android.anchors.AnchorsManager
import com.effective.android.sample.data.Datas
import com.effective.android.sample.util.ProcessUtils

/**
 * kotlin demo
 */
class SampleApplication : Application() {

    private var anchorsManager: AnchorsManager? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks()
        Log.d(TAG, "SampleApplication#onCreate process Id is " + ProcessUtils.processId)
        Log.d(TAG, "SampleApplication#onCreate process Name is " + ProcessUtils.processName)
        Log.d(TAG, "SampleApplication#onCreate - start")
        initDependenciesCompatMultiProcess()
        Log.d(TAG, "SampleApplication#onCreate - end")
    }

    private fun initDependenciesCompatMultiProcess() {
        val processName = ProcessUtils.processName ?: return

        //主进程 com.effective.android.sample
        anchorsManager = when {
            processName == packageName -> {
                Log.d(TAG, "SampleApplication#initDependenciesCompatMutilProcess - startFromApplicationOnMainProcess")
//                Datas().startFromApplicationOnMainProcessByDsl()
                Datas().startFromApplicationOnMainProcessByDsl_1()

                //私有进程 com.effective.android.sample:remote
            }

            processName.startsWith(packageName) -> {
                Log.d(TAG, "SampleApplication#initDependenciesCompatMutilProcess - startFromApplicationOnPrivateProcess")
                Datas().startFromApplicationOnPrivateProcess()

                //公有进程 .public
            }

            else -> {
                Log.d(TAG, "SampleApplication#initDependenciesCompatMutilProcess - startFromApplicationOnPublicProcess")
                Datas().startFromApplicationOnPublicProcess()
            }
        }
    }

    private fun registerActivityLifecycleCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                super.onActivityPreCreated(activity, savedInstanceState)
                Log.d(TAG, "onActivityPreCreated: ${activity.componentName} enter")
                anchorsManager?.waitAnchorTaskFinished()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                Log.d(TAG, "onActivityCreated: ${activity.componentName} enter")
            }

            override fun onActivityStarted(activity: Activity) {

            }

            override fun onActivityResumed(activity: Activity) {

            }

            override fun onActivityPaused(activity: Activity) {

            }

            override fun onActivityStopped(activity: Activity) {

            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

            }

            override fun onActivityDestroyed(activity: Activity) {

            }

        })
    }

    companion object {
        private val TAG: String = "SampleApplication"
    }
}