package com.example.homehealth.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.homehealth.data.local.dao.AlertDao
import com.example.homehealth.data.local.dao.FamilyMemberDao
import com.example.homehealth.data.local.dao.HealthRecordDao
import com.example.homehealth.data.local.dao.LlmCallLogDao
import com.example.homehealth.data.local.dao.MedicalDocumentDao
import com.example.homehealth.data.local.dao.MedicationReminderDao
import com.example.homehealth.data.local.dao.QAHistoryDao
import com.example.homehealth.data.local.entity.Alert
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.data.local.entity.LlmCallLog
import com.example.homehealth.data.local.entity.MedicalDocument
import com.example.homehealth.data.local.entity.MedicationReminder
import com.example.homehealth.data.local.entity.QAHistory

@Database(
    entities = [
        FamilyMember::class,
        HealthRecord::class,
        MedicalDocument::class,
        Alert::class,
        MedicationReminder::class,
        QAHistory::class,
        LlmCallLog::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun familyMemberDao(): FamilyMemberDao
    abstract fun healthRecordDao(): HealthRecordDao
    abstract fun medicalDocumentDao(): MedicalDocumentDao
    abstract fun alertDao(): AlertDao
    abstract fun medicationReminderDao(): MedicationReminderDao
    abstract fun qaHistoryDao(): QAHistoryDao
    abstract fun llmCallLogDao(): LlmCallLogDao
}
