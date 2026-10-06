package com.example.homehealth.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.data.local.AppDatabase
import com.example.homehealth.data.local.dao.HealthRecordDao
import com.example.homehealth.data.local.dao.MedicalDocumentDao
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.data.local.entity.MedicalDocument
import com.example.homehealth.data.local.entity.ParseStatus
import com.example.homehealth.data.remote.LlmClient
import com.example.homehealth.data.remote.LlmProviders
import com.example.homehealth.domain.model.ParseResult
import com.example.homehealth.domain.repository.DocumentRepository
import com.example.homehealth.util.FileUtils
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicalDocumentDao: MedicalDocumentDao,
    private val healthRecordDao: HealthRecordDao,
    private val database: AppDatabase,
    private val settingsPrefs: SettingsPrefs,
    private val llmClient: LlmClient,
    private val gson: Gson
) : DocumentRepository {

    /** 进程内正在解析的文档 ID：僵尸状态清理必须避开它们，否则与在飞解析竞态 */
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 上次僵尸状态清理的时间戳：用于限频（见 [STUCK_RESET_INTERVAL_MS]） */
    @Volatile
    private var lastStuckResetAt = 0L

    override fun observeDocuments(memberId: String): Flow<List<MedicalDocument>> =
        medicalDocumentDao.observeByMember(memberId)

    override suspend fun getDocument(id: String): MedicalDocument? = medicalDocumentDao.getById(id)

    override suspend fun getAll(): List<MedicalDocument> = medicalDocumentDao.getAll()

    override suspend fun saveImageAndCreateDocument(uri: Uri, memberId: String): MedicalDocument =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DOCUMENTS_DIR).apply { mkdirs() }
            val fileName = "doc_${UUID.randomUUID()}.jpg"
            val dest = File(dir, fileName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("无法读取所选图片")
                val document = MedicalDocument(
                    id = UUID.randomUUID().toString(),
                    memberId = memberId,
                    fileName = fileName,
                    filePath = dest.absolutePath,
                    uploadDate = System.currentTimeMillis(),
                    documentType = "lab_report",
                    parseStatus = ParseStatus.PROCESSING
                )
                medicalDocumentDao.insert(document)
                document
            } catch (e: Throwable) {
                dest.delete()
                throw e
            }
        }

    /**
     * 删除来源图片文件（仅限本应用 FileProvider 暴露的 documents/ 目录）。
     * 拍照路径会先建一个临时文件交给相机写入，保存时又复制出一份正式文件；
     * 不清理的话每张报告在设备上都要占两份空间。
     */
    override suspend fun deleteSourceImageIfOwned(uri: Uri) = withContext(Dispatchers.IO) {
        if (uri.scheme != "content" ||
            uri.authority != "${context.packageName}.fileprovider") return@withContext
        val segments = uri.pathSegments.orEmpty()
        // FileProvider 路径形如 /documents/<文件名>；不匹配的一律不动（相册等外部来源）
        if (segments.size != 2 || segments[0] != DOCUMENTS_DIR) return@withContext
        val name = segments[1]
        if (name.isBlank() || name.contains("..")) return@withContext
        runCatching {
            val file = File(File(context.filesDir, DOCUMENTS_DIR), name)
            if (file.exists()) file.delete()
        }
        Unit
    }

    /** 清理拍照中途取消留下的 0 字节图片（相机已建文件但用户没拍成） */
    override suspend fun cleanupEmptyImages() = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DOCUMENTS_DIR)
        if (!dir.isDirectory) return@withContext
        // 留出静置期，避免误删相机正在写入的临时文件
        val cutoff = System.currentTimeMillis() - EMPTY_FILE_GRACE_MS
        dir.listFiles().orEmpty().forEach { f ->
            if (f.isFile && f.length() == 0L && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
        Unit
    }

    override suspend fun parseDocument(
        document: MedicalDocument,
        documentType: String?
    ): ParseResult = withContext(Dispatchers.IO) {
        // 本地模式无法解析报告图片
        if (!LlmProviders.isDirect(settingsPrefs.parseProvider)) {
            throw IllegalStateException(
                "报告解析服务为本地模式，无法解析报告。请在「设置 → 报告解析服务」中选择供应商并配置。"
            )
        }
        if (!llmClient.parseConfigured()) {
            throw IllegalStateException(
                "解析服务未配置 API Key，请在「设置 → 报告解析服务」中填写"
            )
        }
        // LLM 供应商直连（智谱 / OpenAI / Gemini / DeepSeek / Kimi / 通义千问 / Anthropic）
        // 标记为「在飞」：期间进行的僵尸状态清理不得把它误判为中断
        inFlight.add(document.id)
        try {
            try {
                val base64 = FileUtils.compressImageToBase64(File(document.filePath))
                val result = llmClient.parseHealthDocument(base64)
                // 即使没有提取出结构化指标，也保留模型原文供用户排查和后续重试。
                medicalDocumentDao.updateRawText(document.id, result.rawText)
                if (result.records.isEmpty()) {
                    throw IllegalStateException(
                        "未能从报告中识别出健康指标，请拍清晰完整后重试，或手动录入指标"
                    )
                }
                result
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("解析失败：${e.message}", e)
            }
        } finally {
            inFlight.remove(document.id)
        }
    }

    override suspend fun confirmRecords(
        document: MedicalDocument,
        records: List<HealthRecord>,
        normalizationNotes: Map<String, String>
    ) {
        database.withTransaction {
            val current = medicalDocumentDao.getById(document.id)
                ?: throw IllegalStateException("报告已被删除")
            require(records.all { it.memberId == current.memberId && it.sourceDocumentId == current.id }) {
                "报告与健康记录所属成员不一致"
            }
            if (current.parseStatus == ParseStatus.COMPLETED) return@withTransaction
            // 兼容旧版可能留下的部分记录；同一报告重试只保留一组确认后的数据。
            healthRecordDao.deleteBySourceDocument(current.id)
            healthRecordDao.insertAll(records)
            medicalDocumentDao.update(
                current.copy(
                    parseStatus = ParseStatus.COMPLETED,
                    extractedJson = gson.toJson(records),
                    normalizationNotesJson = gson.toJson(normalizationNotes),
                    errorMessage = null
                )
            )
        }
    }

    override suspend fun markFailed(document: MedicalDocument, error: String?) {
        medicalDocumentDao.updateStatus(document.id, ParseStatus.FAILED, error)
    }

    override suspend fun markProcessing(document: MedicalDocument) {
        medicalDocumentDao.updateStatus(document.id, ParseStatus.PROCESSING, null)
    }

    /**
     * 僵尸状态清理：把崩溃 / 被杀进程遗留的 PROCESSING 文档重置为 FAILED（可重试）。
     *
     * 两道保护，缺一不可：
     * 1. **限频**（[STUCK_RESET_INTERVAL_MS]）—— 否则用户每次进入上传页（新 ViewModel 实例）
     *    都会触发一遍。刻意用"限频"而不是"每进程一次"：进程长期驻留时也要能兜底清理；
     * 2. 跳过 [inFlight] 中正在解析的文档 —— 否则在解析进行中再次进入上传页，
     *    会把本次正在跑的解析误判为僵尸并标记失败。
     */
    override suspend fun resetStuckProcessing() {
        val now = System.currentTimeMillis()
        if (now - lastStuckResetAt < STUCK_RESET_INTERVAL_MS) return
        lastStuckResetAt = now

        val stuck = medicalDocumentDao.getByStatus(ParseStatus.PROCESSING)
        stuck.filterNot { it.id in inFlight }.forEach {
            medicalDocumentDao.updateStatus(
                it.id, ParseStatus.FAILED, "解析被中断（应用退出或内存不足），请点击重试"
            )
        }
    }

    companion object {
        /** 报告图片的私有目录（与 res/xml/file_paths.xml 中 FileProvider 暴露的路径一致） */
        private const val DOCUMENTS_DIR = "documents"

        /** 0 字节文件的静置期：超过该时长才判定为遗留垃圾，而非相机正在写入 */
        private const val EMPTY_FILE_GRACE_MS = 60_000L

        /** 僵尸状态清理的最小间隔：进程长期驻留时也能兜底，同时避免反复扫表 */
        private const val STUCK_RESET_INTERVAL_MS = 10L * 60 * 1000
    }
}
