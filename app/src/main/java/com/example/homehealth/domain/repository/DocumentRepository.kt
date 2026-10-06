package com.example.homehealth.domain.repository

import android.net.Uri
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.data.local.entity.MedicalDocument
import com.example.homehealth.domain.model.ParseResult
import kotlinx.coroutines.flow.Flow

/** 医疗文档仓库：保存文件、调用解析、确认入库 */
interface DocumentRepository {
    fun observeDocuments(memberId: String): Flow<List<MedicalDocument>>
    suspend fun getDocument(id: String): MedicalDocument?
    suspend fun getAll(): List<MedicalDocument>

    /** 将图片复制到应用私有目录并创建 PROCESSING 状态的文档记录 */
    suspend fun saveImageAndCreateDocument(uri: Uri, memberId: String): MedicalDocument

    /** 删除已复制完成的来源图片文件（仅限应用私有目录内的拍照临时文件），避免同一次拍照占两份空间 */
    suspend fun deleteSourceImageIfOwned(uri: Uri)

    /** 清理拍照中途取消留下的 0 字节图片文件 */
    suspend fun cleanupEmptyImages()

    /** 解析文档（调用远程 OCR/LLM 服务，未启用或失败时抛出异常） */
    suspend fun parseDocument(document: MedicalDocument, documentType: String? = null): ParseResult

    /** 用户确认后：保存健康记录，文档状态置为 COMPLETED */
    suspend fun confirmRecords(
        document: MedicalDocument,
        records: List<HealthRecord>,
        normalizationNotes: Map<String, String>
    )


    suspend fun markFailed(document: MedicalDocument, error: String?)

    suspend fun markProcessing(document: MedicalDocument)

    /** 把遗留的 PROCESSING 状态（应用中途退出/崩溃导致）重置为 FAILED，使其可重试 */
    suspend fun resetStuckProcessing()
}
