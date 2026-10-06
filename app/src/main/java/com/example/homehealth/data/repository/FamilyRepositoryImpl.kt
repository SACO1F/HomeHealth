package com.example.homehealth.data.repository

import android.content.Context
import com.example.homehealth.data.local.dao.AlertDao
import com.example.homehealth.data.local.dao.FamilyMemberDao
import com.example.homehealth.data.local.dao.MedicalDocumentDao
import com.example.homehealth.data.local.dao.MedicationReminderDao
import com.example.homehealth.data.local.dao.QAHistoryDao
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.domain.repository.FamilyRepository
import com.example.homehealth.util.CalendarEventHelper
import com.example.homehealth.worker.MedicationAlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FamilyRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val familyMemberDao: FamilyMemberDao,
    private val alertDao: AlertDao,
    private val medicationReminderDao: MedicationReminderDao,
    private val medicalDocumentDao: MedicalDocumentDao,
    private val qaHistoryDao: QAHistoryDao
) : FamilyRepository {

    override fun observeMembers(): Flow<List<FamilyMember>> = familyMemberDao.observeAll()

    override suspend fun getMember(id: String): FamilyMember? = familyMemberDao.getById(id)

    override suspend fun getMembers(): List<FamilyMember> = familyMemberDao.getAll()

    override suspend fun upsertMember(member: FamilyMember) = familyMemberDao.upsert(member)

    /**
     * 删除成员。
     *
     * 顺序很关键：**先清理数据库之外的外部资源，再删数据库行**。
     * 日历事件 ID 存在 `medication_reminders.calendarEventIds`、报告原图路径存在
     * `medical_documents.filePath`、问答附图路径存在 `qa_history.imagePath` —— 一旦先删了行，
     * 这三类信息就再也拿不回来，结果是用户日历里留下永久的孤儿日程、
     * 应用私有目录里留下体检报告原图与问答附图（后者是隐私问题）。
     */
    override suspend fun deleteMember(member: FamilyMember) {
        // 1) 系统日历：复用「删除单个提醒」的双通道清理（事件 ID 精确删 + 标题/描述签名兜底）
        medicationReminderDao.getByMember(member.id).forEach { reminder ->
            MedicationAlarmScheduler.cancel(context, reminder)
            runCatching {
                CalendarEventHelper.deleteMedicationEvents(
                    context = context,
                    medicationName = reminder.medicationName,
                    memberName = member.name,
                    storedEventIds = reminder.calendarEventIdList(),
                    reminderId = reminder.id
                )
            }
        }

        // 2) 报告原图：存在 filesDir/documents 下，删数据库行不会带走文件
        medicalDocumentDao.getByMember(member.id).forEach { document ->
            runCatching {
                val file = File(document.filePath)
                if (file.exists()) file.delete()
            }
        }

        // 3) 问答附图：存在 filesDir/qa_images 下。与报告原图同理 ——
        //    必须先取路径再删行，否则 qa_images/ 里会留下永久孤儿文件（隐私问题）
        qaHistoryDao.getImagePathsByMember(member.id).forEach { path ->
            runCatching {
                val file = File(path)
                if (file.exists()) file.delete()
            }
        }

        // 4) 成员头像文件（与编辑成员时的清理口径保持一致）
        runCatching {
            member.avatarUrl?.takeIf { it.isNotBlank() }?.let { path ->
                val avatar = File(path)
                if (avatar.exists()) avatar.delete()
            }
        }

        // 5) 数据库：health_records 通过外键级联删除，其余表手动清理
        familyMemberDao.delete(member)
        alertDao.deleteByMember(member.id)
        medicationReminderDao.deleteByMember(member.id)
        medicalDocumentDao.deleteByMember(member.id)
        qaHistoryDao.deleteByMember(member.id)
    }
}
