package com.example.homehealth.ui.screens.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.homehealth.R
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.data.local.entity.Alert
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.data.local.entity.MedicationReminder
import com.example.homehealth.data.local.entity.MedicalDocument
import com.example.homehealth.data.local.entity.QAHistory
import com.example.homehealth.ui.components.MemberHealthInfo
import com.example.homehealth.ui.components.withHealthInfo
import com.example.homehealth.domain.repository.AlertRepository
import com.example.homehealth.domain.repository.DocumentRepository
import com.example.homehealth.domain.repository.FamilyRepository
import com.example.homehealth.domain.repository.HealthRecordRepository
import com.example.homehealth.domain.repository.LlmCallLogRepository
import com.example.homehealth.domain.repository.MedicationReminderRepository
import com.example.homehealth.domain.model.LlmCallRecord
import com.example.homehealth.domain.model.LlmCallStats
import com.example.homehealth.domain.repository.QARepository
import com.example.homehealth.worker.DailyCheckWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** 导出事件 */
sealed interface SettingsEvent {
    data class ExportReady(val file: File) : SettingsEvent
    data class CheckEnqueued(val message: String) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val familyRepository: FamilyRepository,
    private val healthRecordRepository: HealthRecordRepository,
    private val alertRepository: AlertRepository,
    private val medicationReminderRepository: MedicationReminderRepository,
    private val documentRepository: DocumentRepository,
    private val qaRepository: QARepository,
    private val llmCallLogRepository: LlmCallLogRepository,
    private val settingsPrefs: SettingsPrefs,
    private val gson: Gson
) : ViewModel() {

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events

    val members: StateFlow<List<FamilyMember>> = familyRepository.observeMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 外观模式 ----
    val themeMode = MutableStateFlow(settingsPrefs.themeMode)

    fun setThemeMode(value: String) {
        settingsPrefs.themeMode = value
        themeMode.value = value
    }

    // ---- 语言 ----
    val languageMode = MutableStateFlow(settingsPrefs.languageMode)

    /** 切换语言：持久化 + 应用 per-app locale（Activity 自动重建生效） */
    fun setLanguageMode(value: String) {
        settingsPrefs.languageMode = value
        languageMode.value = value
        val locales = when (value) {
            SettingsPrefs.LANGUAGE_ZH -> LocaleListCompat.forLanguageTags("zh")
            SettingsPrefs.LANGUAGE_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // ---- 报告解析服务配置 ----
    val parseProvider = MutableStateFlow(settingsPrefs.parseProvider)
    val parseApiKey = MutableStateFlow(settingsPrefs.parseApiKey)

    /**
     * 已保存的 Key 存在但无法解密（Keystore 失效），需提示用户重新填写。
     * 声明顺序有意放在 [parseApiKey] 之后：读取 Key 时才会触发解密并刷新该状态。
     */
    val parseKeyUnreadable = MutableStateFlow(settingsPrefs.parseKeyUnreadable)
    val parseModel = MutableStateFlow(settingsPrefs.parseModel)

    fun setParseProvider(value: String) {
        if (settingsPrefs.parseProvider != value) {
            // 切换供应商后旧模型名不再适用，清空以回退到该供应商的默认模型
            settingsPrefs.parseModel = ""
            parseModel.value = ""
        }
        settingsPrefs.parseProvider = value
        parseProvider.value = value
    }

    fun setParseApiKey(value: String) {
        settingsPrefs.parseApiKey = value
        parseApiKey.value = value
        // 重新填写后「解不开」的状态随之解除，提示必须能实时消失
        parseKeyUnreadable.value = settingsPrefs.parseKeyUnreadable
    }

    fun setParseModel(value: String) {
        settingsPrefs.parseModel = value
        parseModel.value = value
    }

    // ---- 健康问答服务配置 ----
    val qaProvider = MutableStateFlow(settingsPrefs.qaProvider)
    val qaApiKey = MutableStateFlow(settingsPrefs.qaApiKey)

    /** 同 [parseKeyUnreadable]，声明顺序同样须在 [qaApiKey] 之后 */
    val qaKeyUnreadable = MutableStateFlow(settingsPrefs.qaKeyUnreadable)
    val qaModel = MutableStateFlow(settingsPrefs.qaModel)

    fun setQaProvider(value: String) {
        if (settingsPrefs.qaProvider != value) {
            // 切换供应商后旧模型名不再适用，清空以回退到该供应商的默认模型
            settingsPrefs.qaModel = ""
            qaModel.value = ""
        }
        settingsPrefs.qaProvider = value
        qaProvider.value = value
    }

    fun setQaApiKey(value: String) {
        settingsPrefs.qaApiKey = value
        qaApiKey.value = value
        qaKeyUnreadable.value = settingsPrefs.qaKeyUnreadable
    }

    // ---- LLM 调用统计（可观测性）----

    /** 统计窗口：近 30 天 */
    private val STATS_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** 最近明细条数 */
    private val RECENT_LIMIT = 10

    private val statsRefresh = MutableStateFlow(0)

    /** 近 30 天的调用统计 */
    val llmStats: StateFlow<LlmCallStats> = statsRefresh
        .map { llmCallLogRepository.stats(STATS_WINDOW_MS) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            LlmCallStats(0, 0, 0, 0, 0, emptyList())
        )

    /** 最近 10 次调用明细 */
    val recentCalls: StateFlow<List<LlmCallRecord>> = statsRefresh
        .map { llmCallLogRepository.recent(RECENT_LIMIT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 进入设置页或用户手动刷新时触发重新统计 */
    fun refreshLlmStats() {
        statsRefresh.value++
    }

    fun setQaModel(value: String) {
        settingsPrefs.qaModel = value
        qaModel.value = value
    }

    fun upsertMember(member: FamilyMember?) {
        viewModelScope.launch {
            familyRepository.upsertMember(
                member ?: return@launch
            )
        }
    }

    fun addMember(
        name: String,
        relationship: String,
        dob: String?,
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
                    dateOfBirth = dob,
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

    fun updateMember(
        existing: FamilyMember,
        name: String,
        relationship: String,
        dob: String?,
        gender: String?,
        heightCm: Double?,
        weightKg: Double?,
        avatarUrl: String? = null,
        health: MemberHealthInfo = MemberHealthInfo()
    ) {
        viewModelScope.launch {
            // 头像被替换或清除时删除旧头像文件
            if (existing.avatarUrl != null && existing.avatarUrl != avatarUrl) {
                runCatching { File(existing.avatarUrl).delete() }
            }
            familyRepository.upsertMember(
                existing.withHealthInfo(health).copy(
                    name = name,
                    relationship = relationship,
                    avatarUrl = avatarUrl,
                    dateOfBirth = dob,
                    gender = gender,
                    heightCm = heightCm,
                    weightKg = weightKg
                )
            )
        }
    }

    fun deleteMember(member: FamilyMember) {
        viewModelScope.launch { familyRepository.deleteMember(member) }
    }

    /** 导出全部数据为 JSON 文件 */
    fun exportData() {
        viewModelScope.launch {
            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val members = familyRepository.getMembers()
                val records = healthRecordRepository.getAllRecords()
                val alerts = alertRepository.getAllAlerts()
                val reminders = medicationReminderRepository.getAll()
                val documents = documentRepository.getAll()
                val qaHistory = qaRepository.getAllHistory()

                val data = mapOf(
                    "exported_at" to SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.CHINA
                    ).format(Date()),
                    "members" to members,
                    "health_records" to records,
                    "alerts" to alerts,
                    "medication_reminders" to reminders,
                    "medical_documents" to documents,
                    "qa_history" to qaHistory
                )
                val dir = File(appContext.cacheDir, "shared").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                val file = File(dir, "homehealth_export_$stamp.json")
                file.writeText(gson.toJson(data))
                file
            }
            _events.emit(SettingsEvent.ExportReady(file))
        }
    }

    /** 立即执行健康检查（用药提醒 + 异常检测） */
    fun runCheckNow() {
        val request = OneTimeWorkRequestBuilder<DailyCheckWorker>().build()
        WorkManager.getInstance(appContext).enqueue(request)
        viewModelScope.launch {
            _events.emit(
                SettingsEvent.CheckEnqueued(appContext.getString(R.string.settings_check_enqueued))
            )
        }
    }
}
