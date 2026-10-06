package com.example.homehealth.ui.screens.familylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.domain.repository.AlertRepository
import com.example.homehealth.domain.repository.FamilyRepository
import com.example.homehealth.domain.repository.HealthRecordRepository
import com.example.homehealth.ui.components.MemberHealthInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 家庭列表卡片数据 */
data class MemberCardUi(
    val member: FamilyMember,
    val latestByType: Map<String, HealthRecord>,
    val unreadAlerts: Int
)

@HiltViewModel
class FamilyListViewModel @Inject constructor(
    private val familyRepository: FamilyRepository,
    private val healthRecordRepository: HealthRecordRepository,
    private val alertRepository: AlertRepository
) : ViewModel() {

    val uiState: StateFlow<List<MemberCardUi>> = combine(
        familyRepository.observeMembers(),
        healthRecordRepository.observeAllRecords(),
        alertRepository.observeAll()
    ) { members, records, alerts ->
        val unreadByMember = alerts
            .filter { !it.alert.isRead }
            .groupingBy { it.alert.memberId }
            .eachCount()
        members.map { member ->
            MemberCardUi(
                member = member,
                latestByType = records
                    .filter { it.memberId == member.id }
                    .groupBy { it.type }
                    .mapValues { (_, list) ->
                        list.maxByOrNull { it.recordDate } ?: list.first()
                    },
                unreadAlerts = unreadByMember[member.id] ?: 0
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMember(
        name: String,
        relationship: String,
        dateOfBirth: String?,
        gender: String?,
        heightCm: Double?,
        weightKg: Double?,
        avatarUrl: String? = null,
        health: MemberHealthInfo = MemberHealthInfo()
    ) {
        viewModelScope.launch {
            familyRepository.upsertMember(
                FamilyMember(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    relationship = relationship,
                    avatarUrl = avatarUrl,
                    dateOfBirth = dateOfBirth,
                    gender = gender,
                    heightCm = heightCm,
                    weightKg = weightKg,
                    bloodType = health.bloodType,
                    waistCm = health.waistCm,
                    exercise = health.exercise,
                    diet = health.diet,
                    smoking = health.smoking,
                    drinking = health.drinking,
                    chronicConditions = health.chronicConditions,
                    surgeryHistory = health.surgeryHistory
                )
            )
        }
    }
}
