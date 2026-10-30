package com.bistu.allthebistu.data.patch

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import kotlinx.serialization.Serializable

/**
 * 备注 / 隐藏 的作用范围。
 *
 * 课表上的一门课按周展开后可能横跨十几周，用户说「这周老师调课」和
 * 「这门课整个学期都不上了」是两件事，所以所有写操作都要先问范围。
 */
object PatchScope {
    /** 只影响某一周 */
    const val WEEK = 0

    /** 影响整门课（所有周） */
    const val ALL = 1
}

/**
 * 对「教务系统里的一条课」做的本地改动。
 *
 * 这里只存**差异**，不存课程本身：教务系统一下次同步、教室换了、
 * 周次变了，改动仍然作用在新数据上，不会把旧课表钉死在本机。
 *
 * 所有字段都可空/可空集合，默认值表示「不改」——
 * 这样序列化出来的 JSON 很小，也方便以后加字段（老文件读进来就是默认值）。
 */
@Serializable
data class CoursePatch(
    /** 认课的稳定键，见 [CoursePatchLogic.keyOf] */
    val key: String = "",
    /** 整门课都隐藏（等于本地删掉） */
    val hidden: Boolean = false,
    /** 只在这几周隐藏 */
    val hiddenWeeks: List<Int> = emptyList(),
    /** 所有周共用的备注 */
    val note: String = "",
    /** 某一周单独的备注，优先级高于 [note] */
    val weekNotes: Map<Int, String> = emptyMap(),
    /** 以下五个是「编辑」写出来的覆盖值，null = 沿用教务系统 */
    val dayOfWeek: Int? = null,
    val beginSection: Int? = null,
    val endSection: Int? = null,
    val room: String? = null,
    val teacher: String? = null
) {
    /** 什么都没改，可以从存储里删掉 */
    val isNoop: Boolean
        get() = !hidden &&
                hiddenWeeks.isEmpty() &&
                note.isBlank() &&
                weekNotes.values.none { it.isNotBlank() } &&
                dayOfWeek == null &&
                beginSection == null &&
                endSection == null &&
                room == null &&
                teacher == null

    /** 指定周该显示哪条备注：本周备注优先，其次整门备注 */
    fun noteFor(week: Int): String =
        weekNotes[week]?.takeIf { it.isNotBlank() } ?: note

    /** 指定周是否被隐藏 */
    fun hides(week: Int): Boolean = hidden || week in hiddenWeeks
}

/**
 * 用户「复制」出来的一条课。
 *
 * 存整份快照而不是只存指向源课的引用：源课下学期没了、
 * 教务系统改了名字，复制出来的那条还得在原地待着。
 *
 * @param id 克隆体的唯一标识，`clone:<id>` 就是它的 [Course.patchKey]
 */
@Serializable
data class CourseClone(
    val id: String,
    val name: String,
    val code: String = "",
    val credit: String = "",
    val teacher: String = "",
    val room: String = "",
    val campus: String = "",
    val weeks: List<Int> = emptyList(),
    val dayOfWeek: Int,
    val beginSection: Int,
    val endSection: Int,
    val colorHex: String = "",
    val teachClassId: String = "",
    /** 复制时一并带过来的备注（不含本周备注，周次已经变了） */
    val note: String = ""
) {
    val patchKey: String get() = CLONE_PREFIX + id

    companion object {
        const val CLONE_PREFIX = "clone:"
    }
}

/** 本机所有课程改动的集合 */
@Serializable
data class CoursePatches(
    val patches: List<CoursePatch> = emptyList(),
    val clones: List<CourseClone> = emptyList()
) {
    fun patchOf(key: String): CoursePatch? =
        if (key.isBlank()) null else patches.firstOrNull { it.key == key }

    val isNoop: Boolean get() = patches.none { !it.isNoop } && clones.isEmpty()

    /** 找出所有非空 patch（序列化前清一遍，避免文件越滚越大） */
    fun pruned(): CoursePatches = copy(
        patches = patches.filterNot { it.isNoop }
    )

    companion object {
        val EMPTY = CoursePatches()
    }
}

/**
 * 把本地改动套到教务系统给的课表上。
 *
 * 纯函数：同样的输入永远得到同样的输出，方便单测，也方便将来做「撤销」。
 *
 * 处理顺序（这个顺序是有意的）：
 *  1. 整门隐藏 → 直接丢掉这条课
 *  2. 本周隐藏 → 从 weeks 里摘掉那几周，摘空了就丢掉
 *  3. 编辑 → 覆盖星期/节次/教室/教师
 *  4. 克隆 → 追加到末尾
 *
 * 「编辑」放在「隐藏」之后：隐藏只认原始周次，改过节的课不会因为
 * 挪了位置就漏掉本周的隐藏标记。
 */
object CoursePatchLogic {

    /**
     * 认课的稳定键。
     *
     * 优先用「课程码 + 教学班 + 星期 + 开始节次」，这是教务系统里
     * 唯一能区分「同一门课不同时间」的组合；缺字段时逐级回退到名称。
     *
     * **不要**把教室放进键里：同一门课 3-5 周在 A 楼、6-13 周在 B 楼时，
     * 会被解析成两条 Course，但用户心里是同一门课，备注和隐藏应该一起生效。
     */
    fun keyOf(course: Course): String {
        if (course.patchKey.isNotBlank()) return course.patchKey
        val identity = course.code.ifBlank { course.name }
        return buildString {
            append(identity)
            append('|')
            append(course.teachClassId.ifBlank { course.name })
            append('|')
            append(course.dayOfWeek)
            append('-')
            append(course.beginSection)
            append('-')
            append(course.endSection)
        }
    }

    fun applyPatches(data: ScheduleData, bundle: CoursePatches): ScheduleData {
        if (bundle.isNoop) {
            // 没有改动时也要写 patchKey：详情页拿到的 course 得能认回自己
            return data.copy(courses = data.courses.map { it.copy(patchKey = keyOf(it)) })
        }

        val out = ArrayList<Course>(data.courses.size + bundle.clones.size)

        data.courses.forEach { original ->
            val key = keyOf(original)
            val patch = bundle.patchOf(key)
            if (patch?.hidden == true) return@forEach

            val weeks = original.weeks.toMutableSet()
            patch?.hiddenWeeks?.forEach { weeks.remove(it) }
            if (weeks.isEmpty()) return@forEach

            out += original.copy(
                dayOfWeek = patch?.dayOfWeek ?: original.dayOfWeek,
                beginSection = patch?.beginSection ?: original.beginSection,
                endSection = patch?.endSection ?: original.endSection,
                room = patch?.room ?: original.room,
                teacher = patch?.teacher ?: original.teacher,
                weeks = weeks,
                patchKey = key
            )
        }

        bundle.clones.forEach { clone ->
            val patch = bundle.patchOf(clone.patchKey)
            if (patch?.hidden == true) return@forEach

            val weeks = clone.weeks.toMutableSet()
            patch?.hiddenWeeks?.forEach { weeks.remove(it) }
            if (weeks.isEmpty()) return@forEach

            out += Course(
                name = clone.name,
                code = clone.code,
                credit = clone.credit,
                teacher = patch?.teacher ?: clone.teacher,
                room = patch?.room ?: clone.room,
                campus = clone.campus,
                weeks = weeks,
                dayOfWeek = patch?.dayOfWeek ?: clone.dayOfWeek,
                beginSection = patch?.beginSection ?: clone.beginSection,
                endSection = patch?.endSection ?: clone.endSection,
                beginTime = "",
                endTime = "",
                colorHex = clone.colorHex,
                teachClassId = clone.teachClassId,
                patchKey = clone.patchKey,
                isClone = true
            )
        }

        return data.copy(courses = out)
    }

    /** 从一门课生成克隆体（复制到别的时段用） */
    fun cloneOf(course: Course, id: String, dayOfWeek: Int, beginSection: Int, endSection: Int): CourseClone =
        CourseClone(
            id = id,
            name = course.name,
            code = course.code,
            credit = course.credit,
            teacher = course.teacher,
            room = course.room,
            campus = course.campus,
            weeks = course.weeks.sorted(),
            dayOfWeek = dayOfWeek,
            beginSection = beginSection,
            endSection = endSection,
            colorHex = course.colorHex,
            teachClassId = course.teachClassId
        )

    /** 写备注：只改这一个作用域，其它字段原样保留 */
    fun withNote(
        bundle: CoursePatches,
        key: String,
        scope: Int,
        week: Int,
        text: String
    ): CoursePatches {
        val trimmed = text.trim()
        val next = bundle.patchOf(key) ?: CoursePatch(key = key)
        val updated = if (scope == PatchScope.WEEK) {
            val notes = next.weekNotes.toMutableMap()
            if (trimmed.isEmpty()) notes.remove(week) else notes[week] = trimmed
            next.copy(weekNotes = notes)
        } else {
            next.copy(note = trimmed)
        }
        return bundle.putPatch(updated)
    }

    /** 隐藏或恢复：整门 / 单周 */
    fun withHidden(
        bundle: CoursePatches,
        key: String,
        scope: Int,
        week: Int,
        hidden: Boolean
    ): CoursePatches {
        val next = bundle.patchOf(key) ?: CoursePatch(key = key)
        val updated = if (scope == PatchScope.WEEK) {
            val weeks = next.hiddenWeeks.toMutableSet()
            if (hidden) weeks += week else weeks -= week
            next.copy(hiddenWeeks = weeks.sorted())
        } else {
            next.copy(hidden = hidden)
        }
        return bundle.putPatch(updated)
    }

    /** 编辑：把表单里的值写进 patch（空串表示清空该字段） */
    fun withEdit(
        bundle: CoursePatches,
        key: String,
        dayOfWeek: Int?,
        beginSection: Int?,
        endSection: Int?,
        room: String?,
        teacher: String?
    ): CoursePatches {
        val next = bundle.patchOf(key) ?: CoursePatch(key = key)
        return bundle.putPatch(
            next.copy(
                dayOfWeek = dayOfWeek,
                beginSection = beginSection,
                endSection = endSection,
                room = room,
                teacher = teacher
            )
        )
    }

    /** 复制：把克隆体追加进去，并把源课的备注带过来 */
    fun withClone(bundle: CoursePatches, clone: CourseClone, sourceNote: String = ""): CoursePatches =
        bundle.copy(clones = bundle.clones + clone).let { base ->
            if (sourceNote.isBlank()) base else withNote(base, clone.patchKey, PatchScope.ALL, 0, sourceNote)
        }

    /** 删掉一条克隆 */
    fun withoutClone(bundle: CoursePatches, key: String): CoursePatches =
        bundle.copy(
            clones = bundle.clones.filterNot { it.patchKey == key },
            patches = bundle.patches.filterNot { it.key == key }
        )

    /** 撤销某门课的全部本地改动 */
    fun withoutPatch(bundle: CoursePatches, key: String): CoursePatches =
        bundle.copy(patches = bundle.patches.filterNot { it.key == key })

    private fun CoursePatches.putPatch(patch: CoursePatch): CoursePatches {
        val rest = patches.filterNot { it.key == patch.key }
        return copy(patches = if (patch.isNoop) rest else rest + patch)
    }
}
