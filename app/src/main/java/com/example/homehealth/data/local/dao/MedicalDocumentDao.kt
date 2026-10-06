package com.example.homehealth.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.homehealth.data.local.entity.MedicalDocument
import com.example.homehealth.data.local.entity.ParseStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicalDocumentDao {

    @Insert
    suspend fun insert(document: MedicalDocument)

    @Update
    suspend fun update(document: MedicalDocument)

    @Query("SELECT * FROM medical_documents WHERE memberId = :memberId ORDER BY uploadDate DESC")
    fun observeByMember(memberId: String): Flow<List<MedicalDocument>>

    @Query("SELECT * FROM medical_documents WHERE id = :id")
    suspend fun getById(id: String): MedicalDocument?

    @Query("SELECT * FROM medical_documents ORDER BY uploadDate DESC")
    suspend fun getAll(): List<MedicalDocument>

    /** 按成员取文档（删除成员时用于清理其本地图片文件） */
    @Query("SELECT * FROM medical_documents WHERE memberId = :memberId")
    suspend fun getByMember(memberId: String): List<MedicalDocument>

    @Query("SELECT * FROM medical_documents WHERE parseStatus = :status")
    suspend fun getByStatus(status: ParseStatus): List<MedicalDocument>

    @Query("UPDATE medical_documents SET parseStatus = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: ParseStatus, error: String?)

    @Query("UPDATE medical_documents SET rawText = :rawText WHERE id = :id")
    suspend fun updateRawText(id: String, rawText: String)

    @Delete
    suspend fun delete(document: MedicalDocument)

    @Query("DELETE FROM medical_documents WHERE memberId = :memberId")
    suspend fun deleteByMember(memberId: String)
}
