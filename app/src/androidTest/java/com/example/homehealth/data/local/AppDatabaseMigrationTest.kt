package com.example.homehealth.data.local

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.homehealth.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移测试。
 *
 * 验证两件事：
 * 1. **迁移后的 schema 与 Room 期望完全一致** —— [MigrationTestHelper.runMigrationsAndValidate]
 *    会用 `app/schemas` 下导出的 schema 做校验，索引名或列定义写错会直接失败。
 *    手写迁移最容易出错的正是索引名（必须逐字等于 `index_<表名>_<列名>`）；
 * 2. **迁移不丢数据**。
 *
 * 起点是 v4：`exportSchema` 从 v4 才开启，更早的 schema 快照（1/2/3.json）不存在，
 * 因此无法构造更早版本的数据库。
 *
 * 运行方式（需连接设备或启动模拟器）：
 * ```
 * gradlew connectedDebugAndroidTest
 * ```
 * ⚠️ 升 DB 版本后要**构建两次**：androidTest assets 的合并不依赖 schema 生成任务，
 * 首次构建可能缺少新增的 `<N>.json`（详见 app/build.gradle.kts 注释）。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /** 完整迁移链 v4 → v9（这是 schema 快照可追溯的最远起点） */
    @Test
    fun migrate4To9_keepsDataAndMatchesSchema() {
        // v4 建库并写入数据（只写各版本都存在的列，不依赖后续新增字段）
        helper.createDatabase(TEST_DB, 4).let { db ->
            insertSampleData(db)
            db.close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            DatabaseModule.MIGRATION_4_5,
            DatabaseModule.MIGRATION_5_6,
            DatabaseModule.MIGRATION_6_7,
            DatabaseModule.MIGRATION_7_8,
            DatabaseModule.MIGRATION_8_9
        )

        assertEquals(1, db.countOf("family_members"))
        assertEquals(1, db.countOf("health_records"))
        assertEquals(1, db.countOf("alerts"))
        assertEquals(1, db.countOf("qa_history"))
        assertEquals(1, db.countOf("medication_reminders"))

        // v5 新增的两个索引必须真实存在
        assertEquals(
            1,
            db.countOf("sqlite_master", "type='index' AND name='index_alerts_memberId'")
        )
        assertEquals(
            1,
            db.countOf(
                "sqlite_master",
                "type='index' AND name='index_health_records_memberId_type_recordDate'"
            )
        )
        // v6/v7/v8 新增的列必须真实存在
        assertTrue(db.hasColumn("health_records", "comparator"))
        assertTrue(db.hasColumn("alerts", "metricType"))
        assertTrue(db.hasColumn("alerts", "baselineText"))
        assertTrue(db.hasColumn("qa_history", "imagePath"))
        assertTrue(db.hasColumn("medical_documents", "rawText"))
        assertTrue(db.hasColumn("medical_documents", "normalizationNotesJson"))
        db.close()
    }

    @Test
    fun migrate8To9_alone_isValid() {
        helper.createDatabase(TEST_DB_89, 8).close()
        val db = helper.runMigrationsAndValidate(
            TEST_DB_89, 9, true, DatabaseModule.MIGRATION_8_9
        )
        assertTrue(db.hasColumn("medical_documents", "rawText"))
        assertTrue(db.hasColumn("medical_documents", "normalizationNotesJson"))
        db.close()
    }

    /** 单独验证最新一跳 v7 → v8（问答附图路径） */
    @Test
    fun migrate7To8_alone_isValid() {
        helper.createDatabase(TEST_DB_78, 7).let { db ->
            insertSampleData(db)
            db.close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_78, 8, true, DatabaseModule.MIGRATION_7_8
        )

        assertTrue(db.hasColumn("qa_history", "imagePath"))
        // 历史条目仍在，且新列默认为 NULL（纯文本提问）
        assertEquals(1, db.countOf("qa_history"))
        assertEquals(1, db.countOf("qa_history", "imagePath IS NULL"))
        db.close()
    }

    /** 单独验证最新一跳 v6 → v7（改动最集中、也最可能出错的一段） */
    @Test
    fun migrate6To7_alone_isValid() {
        helper.createDatabase(TEST_DB_67, 6).let { db ->
            insertSampleData(db)
            db.close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_67, 7, true, DatabaseModule.MIGRATION_6_7
        )

        // 新表必须有全部列
        assertTrue(db.hasColumn("llm_call_logs", "provider"))
        assertTrue(db.hasColumn("llm_call_logs", "latencyMs"))
        assertTrue(db.hasColumn("llm_call_logs", "errorType"))
        // alerts 的结构化字段必须齐全
        assertTrue(db.hasColumn("alerts", "metricType"))
        assertTrue(db.hasColumn("alerts", "baselineText"))
        // 数据仍在
        assertEquals(1, db.countOf("alerts"))
        db.close()
    }

    private fun insertSampleData(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO family_members (id, name, relationship) VALUES ('m1', '测试', '本人')"
        )
        db.execSQL(
            "INSERT INTO health_records (id, memberId, type, value, unit, recordDate) " +
                "VALUES ('r1', 'm1', 'blood_glucose', '5.4', 'mmol/L', 1700000000000)"
        )
        db.execSQL(
            "INSERT INTO alerts (id, memberId, type, title, description, severity, createdDate, isRead) " +
                "VALUES ('a1', 'm1', 'out_of_range', '血糖偏高', 'x', 'MEDIUM', 1700000000000, 0)"
        )
        db.execSQL(
            "INSERT INTO qa_history (id, memberId, question, answer, timestamp) " +
                "VALUES ('q1', 'm1', 'q', 'a', 1700000000000)"
        )
        db.execSQL(
            "INSERT INTO medication_reminders " +
                "(id, memberId, medicationName, dosage, schedule, startDate, active) " +
                "VALUES ('mr1', 'm1', '二甲双胍', '0.5g', 'daily:08:00', 1700000000000, 1)"
        )
    }

    private fun SupportSQLiteDatabase.countOf(table: String, where: String? = null): Int {
        val sql = buildString {
            append("SELECT COUNT(*) FROM ").append(table)
            if (where != null) append(" WHERE ").append(where)
        }
        return query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == column) return true
            }
            false
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_67 = "migration-test-6-7.db"
        const val TEST_DB_78 = "migration-test-7-8.db"
        const val TEST_DB_89 = "migration-test-8-9.db"
    }
}
