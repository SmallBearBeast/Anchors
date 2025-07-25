package com.effective.android.anchors

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import com.effective.android.anchors.log.Logger.d
import com.effective.android.anchors.log.Logger.w
import com.effective.android.anchors.task.Task
import com.effective.android.anchors.task.TaskRuntimeInfo
import com.effective.android.anchors.util.Utils.compareTask
import java.util.Collections
import java.util.concurrent.ExecutorService

/**
 * Anchors 框架 runtime 信息管理
 * 包含：
 * application 锚点管理
 * application UIThreadTask 运行管理
 * 调试配置
 * 线程池配置
 * 运行时 Task 信息收集
 * created by yummylau on 2019/03/12
 */
class AnchorsRuntime {

    //线程池，支持自定义
    private val pool: AnchorThreadPool
    private val obj = Object()
    private val obj2 = Object()
    private val obj3 = Object()

    //如果存在锚点任务，则同步的任务都所有锚点任务都完成前，在 UIThread 上运行
    //ps: 后续解除锚点之后，所有UI线程上的 Task 都通过 handle 发送执行，不保证业务逻辑的同步。
    @Volatile
    var runBlockTask: MutableList<Task> = mutableListOf()

    //设置锚点任务，当且仅当所有锚点任务都完成时, application 不在阻塞 UIThread
    @Volatile
    internal var anchorTaskIds: MutableSet<String> = mutableSetOf()

    //所有 task 运行时信息
    private val runtimeInfo: MutableMap<String, TaskRuntimeInfo> = HashMap()
    internal var debuggable = false
    internal val handler = Handler(Looper.getMainLooper())

    //Task 比较逻辑
    internal val taskComparator: Comparator<Task> = Comparator { lhs, rhs -> compareTask(lhs, rhs) }

    constructor(executor: ExecutorService? = null) {
        pool = AnchorThreadPool(executor)
    }

    internal fun clear() {
        debuggable = false
        anchorTaskIds.clear()
        runBlockTask.clear()
        runtimeInfo.clear()
    }

    internal fun addAnchorTasks(ids: Set<String>) {
        synchronized(obj2) {
            if (ids.isNotEmpty()) {
                anchorTaskIds.addAll(ids)
            }
        }
    }

    internal fun removeAnchorTask(id: String) {
        synchronized(obj2) {
            if (!TextUtils.isEmpty(id)) {
                anchorTaskIds.remove(id)
                synchronized(obj3) {
                    obj3.notify()
                }
            }
        }
    }

    internal fun hasAnchorTasks(): Boolean {
        synchronized(obj2) {
            return anchorTaskIds.isNotEmpty()
        }
    }

    private fun addRunTasks(task: Task) {
        synchronized(obj) {
            if (!runBlockTask.contains(task)) {
                runBlockTask.add(task)
                synchronized(obj3) {
                    obj3.notify()
                }
            }
        }
    }

    internal fun tryRunBlockTask() {
        while (hasAnchorTasks()) {
//            try {
//                Thread.sleep(10)
//            } catch (e: InterruptedException) {
//                e.printStackTrace()
//            }
            synchronized(obj3) {
                if (runBlockTask.isEmpty()) {
                    obj3.wait()
                }
            }
            doRunBlockTask()
        }
        // 如果锚点任务在调用tryRunBlockTask之前执行完，需要重新检测运行runBlockTask是否存在任务没完成
        doRunBlockTask()
    }

    private fun doRunBlockTask() {
        while (runBlockTask.isNotEmpty()) {
            synchronized(obj) {
                if (runBlockTask.isNotEmpty()) {
                    Collections.sort(runBlockTask, taskComparator)
                    runBlockTask.removeAt(0).let {
                        if (hasAnchorTasks()) {
                            it.run()
                        } else {
                            handler.post(it)
                            for (blockItem in runBlockTask) {
                                handler.post(blockItem)
                            }
                            runBlockTask.clear()
                        }
                    }
                }
            }
        }
    }

    private fun hasTaskRuntimeInfo(taskId: String): Boolean {
        return runtimeInfo[taskId] != null
    }

    internal fun getTaskRuntimeInfo(taskId: String): TaskRuntimeInfo? {
        return runtimeInfo[taskId]
    }

    internal fun setThreadName(task: Task, threadName: String) {
        val taskRuntimeInfo = runtimeInfo[task.id]
        if (taskRuntimeInfo != null) {
            taskRuntimeInfo.threadName = threadName
        }
    }

    internal fun setStateInfo(task: Task) {
        val taskRuntimeInfo = runtimeInfo[task.id]
        taskRuntimeInfo?.setStateTime(task.state, System.currentTimeMillis())
    }

    internal fun executeTask(task: Task) {
        if (task.isAsyncTask) {
            pool.getExecutorService().execute(task)
        } else {
            // 主线程直接运行
            if (Looper.getMainLooper() == Looper.myLooper()) {
                task.run()
            } else {
                // 如果有锚点任务还未执行完毕，且当前在子线程，不能post到主线程执行，可能会造成死锁，需要添加到列表里等主线程获取时间片轮训执行。
                // 如果锚点任务已经执行完毕，则直接post到主线程执行
                if (!hasAnchorTasks()) {
                    handler.post(task)
                } else {
                    addRunTasks(task)
                }
            }
        }
    }

    /**
     * 遍历依赖树并完成启动前的初始化
     * 1.获取依赖树最大深度
     * 2.遍历初始化运行时数据并打印log
     * 3.如果锚点不存在，则移除
     * 4.提升锚点链的优先级
     *
     * @param task
     */
    internal fun traversalDependenciesAndInit(task: Task) {

        val traversalVisitor: LinkedHashSet<Task> = linkedSetOf()
        traversalVisitor.add(task)
        traversalDependenciesAndInit(task, traversalVisitor)

        val iterator = anchorTaskIds.iterator()
        while (iterator.hasNext()) {
            val taskId = iterator.next()
            if (!hasTaskRuntimeInfo(taskId)) {
                if (debuggable) {
                    w(Constants.ANCHORS_INFO_TAG, "anchor \"$taskId\" no found !")
                }
                iterator.remove()
            } else {
                val info = getTaskRuntimeInfo(taskId)
                traversalMaxTaskPriority(info?.task)
            }
        }
    }

    /**
     * 回溯算法遍历依赖树，初始化任务，并记录log
     *
     * 如果单条依赖线上存在重复依赖将抛出异常（会造成依赖回环）
     */
    private fun traversalDependenciesAndInit(task: Task, traversalVisitor: LinkedHashSet<Task>) {
        task.bindRuntime(this)
        val taskRuntimeInfo = getTaskRuntimeInfo(task.id)
        if (taskRuntimeInfo == null) {
            // 如果没有初始化则初始化runtimeInfo
            val info = TaskRuntimeInfo(task)

            if (anchorTaskIds.contains(task.id)) {
                info.isAnchor = true
            }
            runtimeInfo[task.id] = info
        } else {
            if (!taskRuntimeInfo.isTaskInfo(task)) {
                throw RuntimeException("Multiple different tasks are not allowed to contain the same id (${task.id})!")
            }
        }

        for (nextTask in task.behindTasks) {
            if (!traversalVisitor.contains(nextTask)) {
                traversalVisitor.add(nextTask)
            } else {
                throw RuntimeException("Do not allow dependency graphs to have a loopback！Related task'id is ${task.id} !")
            }

            if (debuggable && nextTask.behindTasks.isEmpty()) {
                val iterator = traversalVisitor.iterator()
                val builder = StringBuilder()
                while (iterator.hasNext()) {
                    builder.append(iterator.next().id)
                    builder.append(" --> ")
                }
                // traversalVisitor 一定不为空，故可以 length-5
                if (debuggable) {
                    d(Constants.DEPENDENCE_TAG, builder.substring(0, builder.length - 5))
                }
            }

            traversalDependenciesAndInit(nextTask, traversalVisitor)

            traversalVisitor.remove(nextTask)
        }
    }

    /**
     * 递归向上设置优先级
     *
     * @param task
     */
    private fun traversalMaxTaskPriority(task: Task?) {
        if (task == null) {
            return
        }
        task.priority = Int.MAX_VALUE
        for (dependence in task.dependTasks) {
            traversalMaxTaskPriority(dependence)
        }
    }
}