package com.example.study1.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.example.study1.data.Course
import com.example.study1.data.ScheduleData

// ═══════════════════════════════════════════════════════════════
// 一、实体
// ═══════════════════════════════════════════════════════════════

/**
 * 课程表。
 * 主键用「学期+课程代码+星期+开始节次+教室」组合，保证唯一。
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val termCode: String,
    val name: String,
    val code: String,
    val credit: String,
    val teacher: String,
    val room: String,
    val campus: String,
    /** 周次，逗号分隔，如 "3,4,5,6,7,8,9,10,11,12" */
    val weeksCsv: String,
    val dayOfWeek: Int,
    val beginSection: Int,
    val endSection: Int,
    val beginTime: String,
    val endTime: String,
    val colorHex: String,
    val teachClassId: String
)

/** 课表元信息（学生、学期、最后更新时间） */
@Entity(tableName = "schedule_meta")
data class ScheduleMetaEntity(
    @PrimaryKey val termCode: String,
    val termName: String,
    val studentName: String,
    val studentId: String,
    val major: String,
    val className: String,
    val campus: String,
    /** 最后同步时间（毫秒） */
    val lastSyncAt: Long
)

// ═══════════════════════════════════════════════════════════════
// 二、DAO
// ═══════════════════════════════════════════════════════════════

@Dao
interface ScheduleDao {

    // ── 课程 ──
    @Query("SELECT * FROM courses WHERE termCode = :termCode ORDER BY dayOfWeek, beginSection")
    suspend fun getCourses(termCode: String): List<CourseEntity>

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, beginSection")
    suspend fun getAllCourses(): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>)

    @Query("DELETE FROM courses WHERE termCode = :termCode")
    suspend fun deleteCourses(termCode: String)

    @Query("SELECT COUNT(*) FROM courses WHERE termCode = :termCode")
    suspend fun countCourses(termCode: String): Int

    // ── 元信息 ──
    @Query("SELECT * FROM schedule_meta WHERE termCode = :termCode")
    suspend fun getMeta(termCode: String): ScheduleMetaEntity?

    @Query("SELECT * FROM schedule_meta ORDER BY lastSyncAt DESC LIMIT 1")
    suspend fun getLatestMeta(): ScheduleMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(meta: ScheduleMetaEntity)

    @Query("DELETE FROM schedule_meta")
    suspend fun clearAllMeta()

    // ── 清空全部 ──
    @Query("DELETE FROM courses")
    suspend fun clearAllCourses()
}

// ═══════════════════════════════════════════════════════════════
// 三、数据库
// ═══════════════════════════════════════════════════════════════

@Database(
    entities = [CourseEntity::class, ScheduleMetaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        private const val DB_NAME = "schedule.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

// ═══════════════════════════════════════════════════════════════
// 四、实体 ↔ 模型 转换
// ═══════════════════════════════════════════════════════════════

fun Course.toEntity(termCode: String): CourseEntity {
    // 主键：学期+课程代码+星期+节次+教室（同一门课不同时间段是不同记录）
    val id = "${termCode}_${code}_${dayOfWeek}_${beginSection}_${room}"
    return CourseEntity(
        id = id,
        termCode = termCode,
        name = name,
        code = code,
        credit = credit,
        teacher = teacher,
        room = room,
        campus = campus,
        weeksCsv = weeks.sorted().joinToString(","),
        dayOfWeek = dayOfWeek,
        beginSection = beginSection,
        endSection = endSection,
        beginTime = beginTime,
        endTime = endTime,
        colorHex = colorHex,
        teachClassId = teachClassId
    )
}

fun CourseEntity.toCourse(): Course = Course(
    name = name,
    code = code,
    credit = credit,
    teacher = teacher,
    room = room,
    campus = campus,
    weeks = weeksCsv.split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet(),
    dayOfWeek = dayOfWeek,
    beginSection = beginSection,
    endSection = endSection,
    beginTime = beginTime,
    endTime = endTime,
    colorHex = colorHex,
    teachClassId = teachClassId
)

fun ScheduleData.toMetaEntity(lastSyncAt: Long): ScheduleMetaEntity =
    ScheduleMetaEntity(
        termCode = termCode,
        termName = termName,
        studentName = studentName,
        studentId = studentId,
        major = major,
        className = className,
        campus = campus,
        lastSyncAt = lastSyncAt
    )
