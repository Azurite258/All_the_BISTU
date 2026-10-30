package com.bistu.allthebistu

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.patch.CourseClone
import com.bistu.allthebistu.data.patch.CoursePatch
import com.bistu.allthebistu.data.patch.CoursePatchLogic
import com.bistu.allthebistu.data.patch.CoursePatches
import com.bistu.allthebistu.data.patch.PatchScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程本地改动（备注 / 隐藏 / 编辑 / 复制）的纯逻辑测试。
 *
 * 这一层不碰 Android、不碰文件：全部是对 [ScheduleData] 的纯变换，
 * 所以能用 JVM 单测守死。UI 那层只负责把参数传进来。
 */
class CoursePatchTest {

    private fun course(
        name: String = "管理学原理",
        code: String = "MGT101",
        room: String = "XXD-202",
        dayOfWeek: Int = 3,
        begin: Int = 3,
        end: Int = 4,
        weeks: Set<Int> = (3..12).toSet(),
        teachClassId: String = "T1"
    ) = Course(
        name = name,
        code = code,
        credit = "2.0",
        teacher = "雷丙寅",
        room = room,
        campus = "主校区",
        weeks = weeks,
        dayOfWeek = dayOfWeek,
        beginSection = begin,
        endSection = end,
        beginTime = "09:50",
        endTime = "11:25",
        colorHex = "#FFF0CC",
        teachClassId = teachClassId
    )

    private fun data(vararg courses: Course) = ScheduleData(
        termCode = "2026-2027-1",
        termName = "2026 秋",
        studentName = "测试",
        studentId = "00000000",
        major = "计算机",
        className = "计科 1 班",
        campus = "主校区",
        courses = courses.toList()
    )

    // ── 认课的键 ──────────────────────────────────────────────

    @Test
    fun `同一个时间的两条记录得到同一个键`() {
        val a = course(room = "WLC-305")
        val b = course(room = "WLC-204")
        assertEquals(CoursePatchLogic.keyOf(a), CoursePatchLogic.keyOf(b))
    }

    @Test
    fun `不同时间的同名课程键不同`() {
        val a = course(begin = 3, end = 4)
        val b = course(begin = 6, end = 7)
        assertTrue(CoursePatchLogic.keyOf(a) != CoursePatchLogic.keyOf(b))
    }

    @Test
    fun `打过补丁的课程用回写进去的键_不会因为改了节次而漂移`() {
        val moved = course(begin = 9, end = 10).copy(patchKey = "MGT101|T1|3-3-4")
        assertEquals("MGT101|T1|3-3-4", CoursePatchLogic.keyOf(moved))
    }

    // ── 隐藏 ─────────────────────────────────────────────────

    @Test
    fun `整门隐藏后课表里就没有这门课`() {
        val key = CoursePatchLogic.keyOf(course())
        val bundle = CoursePatchLogic.withHidden(CoursePatches.EMPTY, key, PatchScope.ALL, 5, true)

        val result = CoursePatchLogic.applyPatches(data(course(), course(name = "高数", code = "MATH1")), bundle)

        assertEquals(1, result.courses.size)
        assertEquals("高数", result.courses.first().name)
    }

    @Test
    fun `只隐藏第 5 周_其它周还在`() {
        val key = CoursePatchLogic.keyOf(course())
        val bundle = CoursePatchLogic.withHidden(CoursePatches.EMPTY, key, PatchScope.WEEK, 5, true)

        val result = CoursePatchLogic.applyPatches(data(course()), bundle)

        assertEquals(1, result.courses.size)
        assertFalse(result.courses.first().isInWeek(5))
        assertTrue(result.courses.first().isInWeek(4))
        assertTrue(result.courses.first().isInWeek(6))
    }

    @Test
    fun `把最后一周也隐藏掉_这门课直接消失`() {
        val key = CoursePatchLogic.keyOf(course(weeks = setOf(5)))
        val bundle = CoursePatchLogic.withHidden(CoursePatches.EMPTY, key, PatchScope.WEEK, 5, true)

        val result = CoursePatchLogic.applyPatches(data(course(weeks = setOf(5))), bundle)

        assertTrue(result.courses.isEmpty())
    }

    @Test
    fun `撤销隐藏可以恢复`() {
        val key = CoursePatchLogic.keyOf(course())
        var bundle = CoursePatchLogic.withHidden(CoursePatches.EMPTY, key, PatchScope.WEEK, 5, true)
        bundle = CoursePatchLogic.withHidden(bundle, key, PatchScope.WEEK, 5, false)

        val result = CoursePatchLogic.applyPatches(data(course()), bundle)

        assertTrue(result.courses.first().isInWeek(5))
        // 没有实际改动时 patch 会被清掉，文件不会越滚越大
        assertTrue(bundle.patches.isEmpty())
    }

    // ── 编辑 ─────────────────────────────────────────────────

    @Test
    fun `编辑星期和节次后_课块挪到新位置但键不变`() {
        val key = CoursePatchLogic.keyOf(course())
        val bundle = CoursePatchLogic.withEdit(
            bundle = CoursePatches.EMPTY,
            key = key,
            dayOfWeek = 5,
            beginSection = 6,
            endSection = 7,
            room = "WLA-505",
            teacher = "张老师"
        )

        val moved = CoursePatchLogic.applyPatches(data(course()), bundle).courses.single()

        assertEquals(5, moved.dayOfWeek)
        assertEquals(6, moved.beginSection)
        assertEquals(7, moved.endSection)
        assertEquals("WLA-505", moved.room)
        assertEquals("张老师", moved.teacher)
        assertEquals(key, moved.patchKey)
    }

    @Test
    fun `清空教室用空串_和沿用原值区分开`() {
        val key = CoursePatchLogic.keyOf(course())
        val bundle = CoursePatchLogic.withEdit(CoursePatches.EMPTY, key, null, null, null, "", null)

        val cleared = CoursePatchLogic.applyPatches(data(course()), bundle).courses.single()

        assertEquals("", cleared.room)
        assertEquals("雷丙寅", cleared.teacher) // 没传的字段保持原样
    }

    // ── 备注 ─────────────────────────────────────────────────

    @Test
    fun `本周备注优先于整门备注`() {
        val key = CoursePatchLogic.keyOf(course())
        var bundle = CoursePatchLogic.withNote(CoursePatches.EMPTY, key, PatchScope.ALL, 0, "带电脑")
        bundle = CoursePatchLogic.withNote(bundle, key, PatchScope.WEEK, 6, "本周调课到 WLA-505")

        val patch = bundle.patchOf(key)!!

        assertEquals("带电脑", patch.noteFor(5))
        assertEquals("本周调课到 WLA-505", patch.noteFor(6))
    }

    @Test
    fun `清空备注会把这条 patch 一起清掉`() {
        val key = CoursePatchLogic.keyOf(course())
        var bundle = CoursePatchLogic.withNote(CoursePatches.EMPTY, key, PatchScope.ALL, 0, "带电脑")
        assertTrue(bundle.patches.isNotEmpty())

        bundle = CoursePatchLogic.withNote(bundle, key, PatchScope.ALL, 0, "   ")

        assertTrue(bundle.patches.isEmpty())
    }

    // ── 复制 ─────────────────────────────────────────────────

    @Test
    fun `复制到周五第 6 节_克隆体周次和原课一致`() {
        val source = course()
        val clone = CoursePatchLogic.cloneOf(source, "abc123", dayOfWeek = 5, beginSection = 6, endSection = 7)

        val result = CoursePatchLogic.applyPatches(
            data(source),
            CoursePatches(patches = emptyList(), clones = listOf(clone))
        )

        assertEquals(2, result.courses.size)
        val copy = result.courses.single { it.isClone }
        assertEquals(5, copy.dayOfWeek)
        assertEquals(6, copy.beginSection)
        assertEquals(source.weeks, copy.weeks)
        assertEquals("clone:abc123", copy.patchKey)
        assertEquals("雷丙寅", copy.teacher)
    }

    @Test
    fun `隐藏克隆体只影响它自己_原课还在`() {
        val source = course()
        val clone = CoursePatchLogic.cloneOf(source, "abc123", 5, 6, 7)
        var bundle = CoursePatches(clones = listOf(clone))
        bundle = CoursePatchLogic.withHidden(bundle, clone.patchKey, PatchScope.ALL, 0, true)

        val result = CoursePatchLogic.applyPatches(data(source), bundle)

        assertEquals(1, result.courses.size)
        assertFalse(result.courses.single().isClone)
    }

    @Test
    fun `删掉克隆记录后课表恢复原样`() {
        val source = course()
        val clone = CoursePatchLogic.cloneOf(source, "abc123", 5, 6, 7)
        val bundle = CoursePatchLogic.withoutClone(CoursePatches(clones = listOf(clone)), clone.patchKey)

        assertEquals(1, CoursePatchLogic.applyPatches(data(source), bundle).courses.size)
    }

    // ── 序列化 ───────────────────────────────────────────────

    @Test
    fun `改动能原样存成 JSON 再读回来`() {
        val key = CoursePatchLogic.keyOf(course())
        var bundle = CoursePatchLogic.withNote(CoursePatches.EMPTY, key, PatchScope.WEEK, 7, "带电脑")
        bundle = CoursePatchLogic.withEdit(bundle, key, 5, 6, 7, "WLA-505", "张老师")
        bundle = CoursePatchLogic.withClone(bundle, CoursePatchLogic.cloneOf(course(), "x1", 1, 9, 10), "带电脑")

        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<CoursePatches>(json.encodeToString(CoursePatches.serializer(), bundle))

        assertEquals(bundle, restored)
        assertEquals("带电脑", restored.patchOf(key)?.noteFor(7))
        assertNotNull(restored.clones.firstOrNull())
    }

    @Test
    fun `空改动时也会写回稳定的键_详情页靠它认回自己`() {
        val result = CoursePatchLogic.applyPatches(data(course()), CoursePatches.EMPTY)
        assertTrue(result.courses.single().patchKey.isNotBlank())
        assertNull(CoursePatchLogic.applyPatches(data(course()), CoursePatches.EMPTY)
            .courses.single().let { CoursePatches.EMPTY.patchOf(it.patchKey) })
    }

    @Test
    fun `克隆体字段齐全`() {
        val clone = CoursePatchLogic.cloneOf(course(), "id1", 2, 1, 2)
        assertEquals("MGT101", clone.code)
        assertEquals("2.0", clone.credit)
        assertEquals("#FFF0CC", clone.colorHex)
        assertEquals(CourseClone.CLONE_PREFIX + "id1", clone.patchKey)
        assertTrue(CoursePatch(key = "k").isNoop)
    }
}
