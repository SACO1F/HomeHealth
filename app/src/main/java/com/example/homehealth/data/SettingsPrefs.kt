package com.example.homehealth.data

import android.content.Context
import android.util.Log
import com.example.homehealth.data.remote.LlmProviders
import com.example.homehealth.util.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用设置（SharedPreferences）。
 * 报告解析服务与健康问答服务各自独立配置：供应商 / API Key / 模型。
 */
@Singleton
class SettingsPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val sp = context.getSharedPreferences("homehealth_settings", Context.MODE_PRIVATE)

    private val _themeModeFlow = MutableStateFlow(sp.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM)

    /** 外观模式流（设置页切换后全局即时生效，无需重启） */
    val themeModeFlow: StateFlow<String> = _themeModeFlow

    init {
        migrateOldServiceMode()
        migrateRemovedServices()
    }

    // ---- 外观模式 ----

    /** 外观模式：system（跟随系统）/ light（浅色）/ dark（深色） */
    var themeMode: String
        get() = _themeModeFlow.value
        set(value) {
            sp.edit().putString(KEY_THEME_MODE, value).apply()
            _themeModeFlow.value = value
        }

    // ---- 语言 ----

    /** 语言模式：system（跟随系统）/ zh（中文）/ en（英文）。切换语言会重建 Activity，无需 Flow */
    var languageMode: String
        get() = sp.getString(KEY_LANGUAGE_MODE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
        set(value) = sp.edit().putString(KEY_LANGUAGE_MODE, value).apply()

    // ---- 首启「隐私与免责」同意 ----

    /**
     * 已同意的说明版本号。
     *
     * 用版本号而不是布尔值：说明内容发生实质变化（数据去向、免责范围）时必须递增
     * [CONSENT_VERSION_CURRENT]，老用户才会重新看到并再次同意 —— 否则改了文案却
     * 没有任何人再读过，等于没改。
     */
    val consentVersion: Int get() = sp.getInt(KEY_CONSENT_VERSION, 0)

    /** 是否已就当前版本的说明取得同意 */
    val hasAcceptedConsent: Boolean get() = consentVersion >= CONSENT_VERSION_CURRENT

    /** 记录同意。只写本地标记，不上报任何地方 */
    fun acceptConsent() =
        sp.edit().putInt(KEY_CONSENT_VERSION, CONSENT_VERSION_CURRENT).apply()

    // ---- 报告解析服务 ----

    /** 解析供应商：local / zhipu / openai / gemini / deepseek / kimi / qwen / anthropic */
    var parseProvider: String
        get() = sp.getString(KEY_PARSE_PROVIDER, LlmProviders.LOCAL) ?: LlmProviders.LOCAL
        set(value) = sp.edit().putString(KEY_PARSE_PROVIDER, value).apply()

    /** 解析服务 API Key（Keystore 加密存储；读取时自动把历史明文升级为密文） */
    var parseApiKey: String
        get() = readSecret(KEY_PARSE_KEY)
        set(value) = writeSecret(KEY_PARSE_KEY, value.trim())

    /** 解析模型（空 = 用供应商默认视觉模型） */
    var parseModel: String
        get() = sp.getString(KEY_PARSE_MODEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_PARSE_MODEL, value.trim()).apply()

    // ---- 健康问答服务 ----

    /** 问答供应商：local / zhipu / openai / gemini / deepseek / kimi / qwen / anthropic */
    var qaProvider: String
        get() = sp.getString(KEY_QA_PROVIDER, LlmProviders.LOCAL) ?: LlmProviders.LOCAL
        set(value) = sp.edit().putString(KEY_QA_PROVIDER, value).apply()

    /** 问答服务 API Key（Keystore 加密存储；读取时自动把历史明文升级为密文） */
    var qaApiKey: String
        get() = readSecret(KEY_QA_KEY)
        set(value) = writeSecret(KEY_QA_KEY, value.trim())

    /** 问答模型（空 = 用供应商默认文本模型） */
    var qaModel: String
        get() = sp.getString(KEY_QA_MODEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_QA_MODEL, value.trim()).apply()

    /** 健康问答上次咨询的成员（重启后自动选中，直接显示历史对话） */
    var qaMemberId: String
        get() = sp.getString(KEY_QA_MEMBER, "") ?: ""
        set(value) = sp.edit().putString(KEY_QA_MEMBER, value).apply()

    // ---- 密钥读写（Keystore 加密 + 历史明文平滑迁移）----

    /** 存在密文但解不开的密钥槽（Keystore 失效时置入），用于给出准确的提示而不是「未填写」 */
    private val unreadableKeys = mutableSetOf<String>()

    /** 解析服务密钥是否存在「已保存但无法解密」的状态 */
    val parseKeyUnreadable: Boolean get() = KEY_PARSE_KEY in unreadableKeys

    /** 问答服务密钥是否存在「已保存但无法解密」的状态 */
    val qaKeyUnreadable: Boolean get() = KEY_QA_KEY in unreadableKeys

    /**
     * 读密钥。历史版本把 Key 以明文存在 SharedPreferences 中，
     * 这里首次读到明文时就地升级为密文（幂等：升级后下次走解密分支）。
     * 解密失败（换机 / Keystore 被清空 / 数据损坏）视为「未配置」，不抛异常，
     * 但会记入 [unreadableKeys] —— 否则用户会看到"未填写"而反复重填同一把 Key。
     */
    private fun readSecret(key: String): String {
        val raw = sp.getString(key, "") ?: ""
        if (raw.isEmpty()) return ""
        if (!SecretStore.isEncrypted(raw)) {
            SecretStore.encrypt(raw)?.let { upgraded -> sp.edit().putString(key, upgraded).apply() }
            unreadableKeys.remove(key)
            return raw
        }
        val plain = SecretStore.decrypt(raw)
        if (plain == null) {
            Log.w(TAG, "$key 解密失败，已按未配置处理（需用户重新填写 Key）")
            unreadableKeys.add(key)
            return ""
        }
        unreadableKeys.remove(key)
        return plain
    }

    /**
     * 写密钥。加密不可用时降级为明文 —— 权衡后的选择：
     * 本应用的第一道防线是 allowBackup=false，已挡住备份导出通道；
     * 若因 Keystore 异常直接拒绝保存，用户将完全无法使用解析与问答，代价更大。
     * 降级写入的值没有加密前缀，下次读取会被自动升级（自愈）。
     */
    private fun writeSecret(key: String, value: String) {
        if (value.isEmpty()) {
            sp.edit().remove(key).apply()
            unreadableKeys.remove(key)
            return
        }
        val stored = SecretStore.encrypt(value)
        if (stored == null) {
            Log.w(TAG, "$key 加密不可用，已降级为明文存储（下次加密成功时会自动升级）")
        }
        sp.edit().putString(key, stored ?: value).apply()
        // 用户重新填写后，之前"解不开"的状态随之解除
        unreadableKeys.remove(key)
    }

    /** 旧版本（单 serviceMode）一次性迁移到双服务配置 */
    private fun migrateOldServiceMode() {
        if (sp.contains(KEY_PARSE_PROVIDER) || !sp.contains("service_mode")) return

        val mode = sp.getString("service_mode", LlmProviders.LOCAL) ?: LlmProviders.LOCAL
        val oldKey = sp.getString("zhipu_api_key", "") ?: ""

        sp.edit()
            .putString(KEY_PARSE_PROVIDER, mode)
            .putString(KEY_QA_PROVIDER, mode)
            .putString(KEY_PARSE_MODEL, "")
            .putString(KEY_QA_MODEL, "")
            // 迁移完就删掉遗留的明文 Key，别让它在设备上继续以明文留存
            .remove("zhipu_api_key")
            .apply()
        if (oldKey.isNotBlank()) {
            writeSecret(KEY_PARSE_KEY, oldKey)
            writeSecret(KEY_QA_KEY, oldKey)
        }
    }

    /** 已下线服务迁移：历史选择了 backend（自建后端）/ custom（自定义服务）的用户迁回本地模式 */
    private fun migrateRemovedServices() {
        val legacyIds = setOf("backend", "custom")
        val parse = sp.getString(KEY_PARSE_PROVIDER, null)
        val qa = sp.getString(KEY_QA_PROVIDER, null)
        if (parse in legacyIds || qa in legacyIds) {
            sp.edit().apply {
                if (parse in legacyIds) putString(KEY_PARSE_PROVIDER, LlmProviders.LOCAL)
                if (qa in legacyIds) putString(KEY_QA_PROVIDER, LlmProviders.LOCAL)
            }.apply()
        }
    }

    companion object {
        private const val TAG = "SettingsPrefs"

        /** 外观模式取值 */
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** 语言模式取值 */
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"

        /**
         * 隐私与免责说明的当前版本。**文案有实质变更时必须 +1**：
         * 老用户的已同意版本号低于它，就会重新看到首启那一屏并需再次同意。
         */
        const val CONSENT_VERSION_CURRENT = 1

        private const val KEY_CONSENT_VERSION = "consent_version"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE_MODE = "language_mode"
        private const val KEY_PARSE_PROVIDER = "parse_provider"
        private const val KEY_PARSE_KEY = "parse_api_key"
        private const val KEY_PARSE_MODEL = "parse_model"
        private const val KEY_QA_PROVIDER = "qa_provider"
        private const val KEY_QA_KEY = "qa_api_key"
        private const val KEY_QA_MODEL = "qa_model"
        private const val KEY_QA_MEMBER = "qa_member_id"
    }
}
