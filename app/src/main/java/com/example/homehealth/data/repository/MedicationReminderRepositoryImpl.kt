package com.example.homehealth.data.repository

import com.example.homehealth.data.local.dao.MedicationReminderDao
import com.example.homehealth.data.local.dao.ReminderWithMemberName
import com.example.homehealth.data.local.entity.MedicationReminder
import com.example.homehealth.domain.repository.MedicationReminderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicationReminderRepositoryImpl @Inject constructor(
    private val medicationReminderDao: MedicationReminderDao
) : MedicationReminderRepository {

    override fun observeAll(): Flow<List<ReminderWithMemberName>> =
        medicationReminderDao.observeAll()

    override suspend fun upsert(reminder: MedicationReminder) =
        medicationReminderDao.upsert(reminder)

    override suspend fun delete(reminder: MedicationReminder) =
        medicationReminderDao.delete(reminder)

    override suspend fun getActive(): List<MedicationReminder> =
        medicationReminderDao.getActive()

    override suspend fun getAll(): List<MedicationReminder> =
        medicationReminderDao.getAll()

    override suspend fun getById(id: String): MedicationReminder? =
        medicationReminderDao.getById(id)
}
