package com.example.homehealth.data.local

/**
 * v4 → v9 迁移 SQL 的单一事实来源。
 *
 * **为什么要抽出来**：Migration 对象只能在设备 / instrumentation 环境里执行，
 * SQL 字符串埋在 `migrate()` 方法体内，JVM 单测拿不到——结构级校验过去只能靠
 * 临时目录里的 Python 脚本，脚本不在仓库里就会丢。SQL 常量化之后，
 * [DatabaseModule] 的迁移对象与 JVM 测试 `SchemaMigrationStructureTest`
 * 共用同一份字符串，任何一端漂移（手改 SQL 忘改快照、加列忘写迁移）都会被测试拦下。
 *
 * v1 → v4 的三条迁移早于 schema 快照（快照从 v4 起才有），无对照物，仍留在 DatabaseModule 内。
 */
object AppMigrationSql {

    /** v4 → v5：补索引（不改变表结构；索引一致性由 androidTest 的真实 Room 校验覆盖） */
    val V4_TO_V5: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS index_alerts_memberId ON alerts(memberId)",
        "CREATE INDEX IF NOT EXISTS index_health_records_memberId_type_recordDate " +
            "ON health_records(memberId, type, recordDate)"
    )

    /** v5 → v6：health_records 新增比较符列（保留已有数据） */
    val V5_TO_V6: List<String> = listOf(
        "ALTER TABLE health_records ADD COLUMN comparator TEXT"
    )

    /**
     * v6 → v7：新增 `llm_call_logs` 表 + alerts 结构化字段 7 列。
     * `llm_call_logs` 的 DDL 必须与 Room 生成的 schema（app/schemas/7.json）逐字一致，
     * 否则打开数据库时的 schema 校验会直接抛 IllegalStateException。
     */
    val V6_TO_V7: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `llm_call_logs` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`provider` TEXT NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`scene` TEXT NOT NULL, " +
            "`latencyMs` INTEGER NOT NULL, " +
            "`promptChars` INTEGER NOT NULL, " +
            "`completionChars` INTEGER NOT NULL, " +
            "`hasImage` INTEGER NOT NULL, " +
            "`attempts` INTEGER NOT NULL, " +
            "`ok` INTEGER NOT NULL, " +
            "`errorType` TEXT, " +
            "`createdAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_llm_call_logs_createdAt` " +
            "ON `llm_call_logs` (`createdAt`)",
        "ALTER TABLE alerts ADD COLUMN metricType TEXT",
        "ALTER TABLE alerts ADD COLUMN direction TEXT",
        "ALTER TABLE alerts ADD COLUMN valueText TEXT",
        "ALTER TABLE alerts ADD COLUMN unitText TEXT",
        "ALTER TABLE alerts ADD COLUMN refText TEXT",
        "ALTER TABLE alerts ADD COLUMN spanText TEXT",
        "ALTER TABLE alerts ADD COLUMN baselineText TEXT"
    )

    /**
     * v7 → v8：qa_history 新增附图路径列（保留已有数据）。
     *
     * 承载「随提问附带的报告图片」——影像 / 病理等叙述性报告无法结构化入库，
     * 只能以图片提问；只有提问时带上图片，历史条目才不会出现「有提问看不到图」。
     * 列可空，历史条目天然为 null（表示纯文本提问）。
     */
    val V7_TO_V8: List<String> = listOf(
        "ALTER TABLE qa_history ADD COLUMN imagePath TEXT"
    )

    /** v8 → v9：保留报告解析原文及每条记录的归一化说明。 */
    val V8_TO_V9: List<String> = listOf(
        "ALTER TABLE medical_documents ADD COLUMN rawText TEXT",
        "ALTER TABLE medical_documents ADD COLUMN normalizationNotesJson TEXT"
    )

    /**
     * v9 → v10：family_members 新增健康档案 8 列（血型 / 腰围 / 运动 / 饮食 /
     * 吸烟 / 饮酒 / 慢性病史 / 手术史），全部可空，历史成员天然为「未填写」。
     * 这些信息只在健康问答拼上下文与成员详情展示时读取，不参与异常检测。
     */
    val V9_TO_V10: List<String> = listOf(
        "ALTER TABLE family_members ADD COLUMN bloodType TEXT",
        "ALTER TABLE family_members ADD COLUMN waistCm REAL",
        "ALTER TABLE family_members ADD COLUMN exercise TEXT",
        "ALTER TABLE family_members ADD COLUMN diet TEXT",
        "ALTER TABLE family_members ADD COLUMN smoking TEXT",
        "ALTER TABLE family_members ADD COLUMN drinking TEXT",
        "ALTER TABLE family_members ADD COLUMN chronicConditions TEXT",
        "ALTER TABLE family_members ADD COLUMN surgeryHistory TEXT"
    )
}
