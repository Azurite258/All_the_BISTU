package com.bistu.allthebistu.ui.schedule

import com.bistu.allthebistu.data.Course

/**
 * 课程详情页的写操作集合。
 *
 * 为什么打包成一个对象：这些回调要从 MainActivity 一路传到
 * ScheduleScreen → ScheduleContent → CourseDetailSheet 三层，
 * 六个 lambda 逐个当参数会把函数签名撑成十几行。打包之后
 * 只在 [ScheduleScreen] 的入参里出现一次，其余层原样透传。
 *
 * 默认实现全是空操作：预览、测试、以及「只想看不想改」的调用方
 * 不用被迫传六个 lambda。
 */
data class CourseActions(
    /** 保存备注。[scope] 见 [com.bistu.allthebistu.data.patch.PatchScope] */
    val onSaveNote: (course: Course, scope: Int, week: Int, text: String) -> Unit = { _, _, _, _ -> },
    /** 删除（本地隐藏）：整门或某一周 */
    val onHide: (course: Course, scope: Int, week: Int) -> Unit = { _, _, _ -> },
    /** 编辑星期 / 节次 / 教室 / 教师 */
    val onEdit: (course: Course, day: Int, begin: Int, end: Int, room: String, teacher: String) -> Unit =
        { _, _, _, _, _, _ -> },
    /** 复制到别的时段 */
    val onClone: (course: Course, day: Int, begin: Int) -> Unit = { _, _, _ -> },
    /** 删掉一条克隆课程 */
    val onRemoveClone: (course: Course) -> Unit = { _ -> },
    /** 撤销这门课的全部本地改动 */
    val onReset: (course: Course) -> Unit = { _ -> }
)
