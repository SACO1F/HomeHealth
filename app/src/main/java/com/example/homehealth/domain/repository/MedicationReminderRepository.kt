package com.example.homehealth.domain.repository

import com.example.homehealth.data.local.dao.ReminderWithMemberName
import com.example.homehealth.data.local.entity.MedicationReminder
import kotlinx.coroutines.flow.Flow

/** 用药提醒仓库 */
interface MedicationReminderRepository {
    fun observeAll(): Flow<List<ReminderWithMemberName>>
    suspend fun upsert(reminder: MedicationReminder)
    suspend fun delete(reminder: MedicationReminder)
    suspend fun getActive(): List<MedicationReminder>
    suspend fun getAll(): List<MedicationReminder>
    suspend fun getById(id: String): MedicationReminder?
}
