package com.example.homehealth.data.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A3：离线迁移结构级校验（JVM，不依赖设备）。
 *
 * 把「用 N.json 的 DDL 建库 → 施加迁移 → 与最新 DDL 逐表比对」从临时目录的 Python 脚本
 * 固化为仓库内测试：从 v4 的 schema 快照出发，把 [AppMigrationSql] 的 SQL 重放到
 * 一个纯内存的「表 → 列 → 亲和性」模型上，逐段与 Room 导出的 5/6/7/8.json 对照。
 *
 * 与 androidTest 的 [AppDatabaseMigrationTest] 互补：那个验证真实 SQLite + Room 校验器
 * （含索引与 NOT NULL 级别），本测试不接设备也能在每次构建时拦截
 * 「迁移 SQL 与 Room 期望不一致」——即 01:56 那次"半迁移烧掉版本号"类事故的静态防线。
 */
class SchemaMigrationStructureTest {

    // ---------- schema 快照加载 ----------

    private val schemaDir: File by lazy { locateSchemaDir() }

    private fun locateSchemaDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir 未设置"))
        repeat(5) {
            val current = dir ?: return@repeat
            listOf(
                File(current, "app/schemas/$SCHEMA_PACKAGE"),
                File(current, "schemas/$SCHEMA_PACKAGE")
            ).firstOrNull { it.isDirectory }?.let { return it }
            dir = current.parentFile
        }
        error("未找到 Room schema 目录（app/schemas/$SCHEMA_PACKAGE）。cwd=${System.getProperty("user.dir")}")
    }

    /** 解析 N.json → 表名 → (列名 → 亲和性)。列名 + 亲和性即结构级校验的比对粒度 */
    private fun schemaOf(version: Int): Map<String, Map<String, String>> {
        val file = File(schemaDir, "$version.json")
        assertTrue("缺少 schema 快照：${file.absolutePath}", file.isFile)
        val database = JSONObject(file.readText()).getJSONObject("database")
        val tables = mutableMapOf<String, MutableMap<String, String>>()
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val columns = mutableMapOf<String, String>()
            val fields = entity.getJSONArray("fields")
            for (j in 0 until fields.length()) {
                val field = fields.getJSONObject(j)
                columns[field.getString("columnName")] = field.getString("affinity").uppercase()
            }
            tables[entity.getString("tableName")] = columns
        }
        return tables
    }

    // ---------- 迁移 SQL 的内存重放 ----------

    private fun apply(tables: MutableMap<String, MutableMap<String, String>>, sqlList: List<String>) {
        for (sql in sqlList) {
            val s = sql.trim()
            ALTER_REGEX.find(s)?.let { m ->
                val (table, column, affinity) = m.destructured
                tables.getOrPut(table) { mutableMapOf() }[column] = affinity.uppercase()
                continue
            }
            CREATE_TABLE_REGEX.find(s)?.let { m ->
                val table = m.groupValues[1]
                val inner = s.substringAfter('(').substringBeforeLast(')')
                val columns = mutableMapOf<String, String>()
                for (raw in splitTopLevel(inner)) {
                    val tokens = raw.replace("`", " ").trim().split(Regex("\\s+"))
                    if (tokens.isEmpty() || tokens[0].isEmpty()) continue
                    if (tokens[0].uppercase() in TABLE_CONSTRAINT_KEYWORDS) continue
                    val affinity = tokens.getOrNull(1) ?: continue
                    columns[tokens[0]] = affinity.uppercase()
                }
                // CREATE TABLE IF NOT EXISTS：表已存在时保持原样（与真实 SQLite 语义一致）
                tables.putIfAbsent(table, columns)
                continue
            }
            // CREATE INDEX / DROP 等语句：表-列结构不受影响，索引一致性由 androidTest 覆盖
        }
    }

    private fun applyAll(from: Int, steps: List<List<String>>): Map<String, Map<String, String>> {
        val tables = schemaOf(from).mapValues { (_, cols) -> cols.toMutableMap() }.toMutableMap()
        for (step in steps) apply(tables, step)
        return tables
    }

    // ---------- 用例 ----------

    @Test
    fun `v4到v5只补索引-表结构不变`() {
        val before = schemaOf(4)
        val after = applyAll(4, listOf(AppMigrationSql.V4_TO_V5))
        assertEquals(before, after)
        assertEquals(schemaOf(5), after)
    }

    @Test
    fun `v5到v6为health_records新增comparator列`() {
        val after = applyAll(5, listOf(AppMigrationSql.V5_TO_V6))
        assertEquals("TEXT", after.getValue("health_records").getValue("comparator"))
        assertEquals(schemaOf(6), after)
    }

    @Test
    fun `v6到v7与Room导出的7json完全一致`() {
        val after = applyAll(6, listOf(AppMigrationSql.V6_TO_V7))
        // 新表列集与快照逐列一致（DDL 与 Room schema 不一致会在打开数据库时炸 schema 校验）
        assertEquals(schemaOf(7).getValue("llm_call_logs"), after.getValue("llm_call_logs"))
        // alerts 新增 7 个结构化字段
        val alerts = after.getValue("alerts")
        for (column in listOf(
            "metricType", "direction", "valueText", "unitText", "refText", "spanText", "baselineText"
        )) {
            assertEquals("TEXT", alerts.getValue(column))
        }
        assertEquals(schemaOf(7), after)
    }

    @Test
    fun `从v4连跑三段迁移-最终结构与7json一致`() {
        val after = applyAll(4, listOf(AppMigrationSql.V4_TO_V5, AppMigrationSql.V5_TO_V6, AppMigrationSql.V6_TO_V7))
        assertEquals(schemaOf(7), after)
    }

    @Test
    fun `v7快照包含全部七张表`() {
        val tables = schemaOf(7).keys
        assertEquals(
            setOf(
                "family_members", "health_records", "medical_documents",
                "alerts", "medication_reminders", "qa_history", "llm_call_logs"
            ),
            tables
        )
    }

    @Test
    fun `v7到v8为qa_history新增imagePath列`() {
        val after = applyAll(7, listOf(AppMigrationSql.V7_TO_V8))
        assertEquals("TEXT", after.getValue("qa_history").getValue("imagePath"))
        // 其余表不受影响：这一跳只加一列
        assertEquals(schemaOf(7).getValue("health_records"), after.getValue("health_records"))
        assertEquals(schemaOf(8), after)
    }

    @Test
    fun `从v4连跑四段迁移-最终结构与8json一致`() {
        val after = applyAll(
            4,
            listOf(
                AppMigrationSql.V4_TO_V5,
                AppMigrationSql.V5_TO_V6,
                AppMigrationSql.V6_TO_V7,
                AppMigrationSql.V7_TO_V8
            )
        )
        assertEquals(schemaOf(8), after)
    }

    @Test
    fun `v8快照包含全部七张表`() {
        val tables = schemaOf(8).keys
        assertEquals(
            setOf(
                "family_members", "health_records", "medical_documents",
                "alerts", "medication_reminders", "qa_history", "llm_call_logs"
            ),
            tables
        )
    }

    @Test
    fun `v8到v9保留解析原文与归一化说明`() {
        val after = applyAll(8, listOf(AppMigrationSql.V8_TO_V9))
        assertEquals("TEXT", after.getValue("medical_documents").getValue("rawText"))
        assertEquals("TEXT", after.getValue("medical_documents").getValue("normalizationNotesJson"))
        assertEquals(schemaOf(9), after)
    }

    @Test
    fun `从v4连续迁移到v9与快照一致`() {
        val after = applyAll(4, listOf(
            AppMigrationSql.V4_TO_V5, AppMigrationSql.V5_TO_V6,
            AppMigrationSql.V6_TO_V7, AppMigrationSql.V7_TO_V8,
            AppMigrationSql.V8_TO_V9
        ))
        assertEquals(schemaOf(9), after)
    }

    @Test
    fun `v9到v10为family_members新增健康档案8列`() {
        val after = applyAll(9, listOf(AppMigrationSql.V9_TO_V10))
        val members = after.getValue("family_members")
        for (column in listOf(
            "bloodType", "waistCm", "exercise", "diet",
            "smoking", "drinking", "chronicConditions", "surgeryHistory"
        )) {
            assertTrue("缺少列 $column", column in members)
        }
        assertEquals("REAL", members.getValue("waistCm"))
        assertEquals("TEXT", members.getValue("bloodType"))
        // 其余表不受影响：这一跳只改 family_members
        assertEquals(schemaOf(9).getValue("health_records"), after.getValue("health_records"))
        assertEquals(schemaOf(10), after)
    }

    @Test
    fun `从v4连续迁移到v10与快照一致`() {
        val after = applyAll(4, listOf(
            AppMigrationSql.V4_TO_V5, AppMigrationSql.V5_TO_V6,
            AppMigrationSql.V6_TO_V7, AppMigrationSql.V7_TO_V8,
            AppMigrationSql.V8_TO_V9, AppMigrationSql.V9_TO_V10
        ))
        assertEquals(schemaOf(10), after)
    }

    private companion object {
        const val SCHEMA_PACKAGE = "com.example.homehealth.data.local.AppDatabase"

        val ALTER_REGEX =
            Regex("(?i)^\\s*ALTER\\s+TABLE\\s+`?(\\w+)`?\\s+ADD\\s+COLUMN\\s+`?(\\w+)`?\\s+(\\w+)")

        val CREATE_TABLE_REGEX =
            Regex("(?i)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\(")

        val TABLE_CONSTRAINT_KEYWORDS = setOf("PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "CONSTRAINT")

        /** 按括号深度为 0 的逗号切分列定义（列内可能有嵌套括号，如外键引用） */
        fun splitTopLevel(text: String): List<String> {
            val parts = mutableListOf<String>()
            val sb = StringBuilder()
            var depth = 0
            for (ch in text) {
                when (ch) {
                    '(' -> {
                        depth++; sb.append(ch)
                    }
                    ')' -> {
                        depth--; sb.append(ch)
                    }
                    ',' -> if (depth == 0) {
                        parts.add(sb.toString()); sb.clear()
                    } else {
                        sb.append(ch)
                    }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotBlank()) parts.add(sb.toString())
            return parts
        }
    }
}
