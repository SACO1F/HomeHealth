package com.example.homehealth.ui.screens.reminders

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.homehealth.data.local.dao.ReminderWithMemberName
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.MedicationReminder
import com.example.homehealth.domain.repository.FamilyRepository
import com.example.homehealth.domain.repository.MedicationReminderRepository
import com.example.homehealth.util.CalendarEventHelper
import com.example.homehealth.worker.MedicationAlarmScheduler
import com.example.homehealth.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class RemindersUiState(
    val reminders: List<ReminderWithMemberName> = emptyList(),
    val members: List<FamilyMember> = emptyList()
)

@HiltViewModel
class RemindersViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val medicationReminderRepository: MedicationReminderRepository,
    private val familyRepository: FamilyRepository
) : ViewModel() {

    private val _errors = MutableSharedFlow<String>()
    val errors = _errors.asSharedFlow()

    private fun hasCalendarAccess(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    val uiState: StateFlow<RemindersUiState> = combine(
        medicationReminderRepository.observeAll(),
        familyRepository.observeMembers()
    ) { reminders, members ->
        RemindersUiState(reminders = reminders, members = members)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemindersUiState())

    fun saveReminder(
        existing: MedicationReminder?,
        memberId: String,
        medicationName: String,
        dosage: String,
        times: List<String>
    ) {
        viewModelScope.launch {
            val base = existing?.copy(
                    memberId = memberId,
                    medicationName = medicationName,
                    dosage = dosage,
                    schedule = MedicationReminder.buildSchedule(times),
                    calendarEventIds = null
                ) ?: MedicationReminder(
                    id = UUID.randomUUID().toString(),
                    memberId = memberId,
                    medicationName = medicationName,
                    dosage = dosage,
                    schedule = MedicationReminder.buildSchedule(times),
                    startDate = System.currentTimeMillis()
                )
            val wasExported = existing?.calendarEventIds != null
            if (wasExported && !hasCalendarAccess()) {
                _errors.emit(appContext.getString(R.string.reminders_calendar_denied))
                return@launch
            }
            var newEventIds = emptyList<Long>()
            try {
                if (existing != null && (wasExported || hasCalendarAccess())) {
                    val oldMemberName = familyRepository.getMember(existing.memberId)?.name
                    val newMemberName = familyRepository.getMember(memberId)?.name
                        ?: throw IllegalStateException("成员已不存在")
                    withContext(Dispatchers.IO) {
                        // 先建新日程；旧日程删除失败时可以撤销新日程，避免编辑后留下孤儿。
                        if (wasExported && base.active) {
                            newEventIds = CalendarEventHelper.insertMedicationEvents(
                                appContext, medicationName, dosage, newMemberName, times, base.id
                            )
                        }
                        CalendarEventHelper.deleteMedicationEvents(
                            appContext, existing.medicationName, oldMemberName,
                            existing.calendarEventIdList(), existing.id,
                            protectedEventIds = newEventIds
                        )
                    }
                }
                medicationReminderRepository.upsert(
                    base.copy(calendarEventIds = if (wasExported)
                        MedicationReminder.buildCalendarEventIds(newEventIds) ?: "" else null)
                )
            } catch (e: Exception) {
                if (newEventIds.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { CalendarEventHelper.deleteCalendarEvents(appContext, newEventIds) }
                }
                if (e is CancellationException) throw e
                _errors.emit(appContext.getString(R.string.reminders_calendar_failed, e.message ?: ""))
                return@launch
            }
            if (existing != null) MedicationAlarmScheduler.cancel(appContext, existing)
            MedicationAlarmScheduler.schedule(appContext, base)
        }
    }

    /** 删除提醒时先清理日历；清理失败则保留本地 ID 以供重试。 */
    fun deleteReminder(item: ReminderWithMemberName) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CalendarEventHelper.deleteMedicationEvents(
                        context = appContext,
                        medicationName = item.reminder.medicationName,
                        memberName = item.memberName,
                        storedEventIds = item.reminder.calendarEventIdList(),
                        reminderId = item.reminder.id
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (item.reminder.calendarEventIds != null) {
                    _errors.emit(appContext.getString(R.string.reminders_calendar_failed, e.message ?: ""))
                    return@launch
                }
            }
            MedicationAlarmScheduler.cancel(appContext, item.reminder)
            medicationReminderRepository.delete(item.reminder)
        }
    }

    /** 记录提醒已写入日历的事件 ID（再次写入时先清理旧事件） */
    suspend fun updateCalendarEventIds(reminder: MedicationReminder, eventIds: List<Long>) {
        medicationReminderRepository.upsert(
            reminder.copy(calendarEventIds = MedicationReminder.buildCalendarEventIds(eventIds))
        )
    }

    fun toggleActive(reminder: MedicationReminder) {
        viewModelScope.launch {
            var updated = reminder.copy(active = !reminder.active)
            if (reminder.calendarEventIds != null) {
                if (!hasCalendarAccess()) {
                    _errors.emit(appContext.getString(R.string.reminders_calendar_denied))
                    return@launch
                }
                try {
                    val memberName = familyRepository.getMember(reminder.memberId)?.name
                        ?: throw IllegalStateException("成员已不存在")
                    updated = withContext(Dispatchers.IO) {
                        if (updated.active) {
                            val ids = CalendarEventHelper.insertMedicationEvents(
                                appContext, reminder.medicationName, reminder.dosage,
                                memberName, reminder.dailyTimes(), reminder.id
                            )
                            updated.copy(calendarEventIds = MedicationReminder.buildCalendarEventIds(ids) ?: "")
                        } else {
                            CalendarEventHelper.deleteMedicationEvents(
                                appContext, reminder.medicationName, memberName,
                                reminder.calendarEventIdList(), reminder.id
                            )
                            updated.copy(calendarEventIds = "")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _errors.emit(appContext.getString(R.string.reminders_calendar_failed, e.message ?: ""))
                    return@launch
                }
            }
            medicationReminderRepository.upsert(updated)
            MedicationAlarmScheduler.cancel(appContext, reminder)
            MedicationAlarmScheduler.schedule(appContext, updated)
        }
    }
}
