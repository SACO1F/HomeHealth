package com.example.homehealth.data.local.entity

import com.example.homehealth.util.DateUtils
import kotlin.math.pow

/** 生活方式与病史档案的选项 code（数据库存储值）与中文文案（供健康问答上下文使用） */
object ProfileCodes {

    val BLOOD_TYPES = listOf("A", "B", "AB", "O")

    val EXERCISE = listOf("sedentary", "light", "moderate", "active", "daily")
    val DIET = listOf("balanced", "vegetarian", "meat", "salty", "oily", "sweet")
    val SMOKING = listOf("never", "quit", "occasional", "daily")
    val DRINKING = listOf("never", "quit", "occasional", "frequent")

    fun exerciseZh(code: String): String = when (code) {
        "sedentary" -> "久坐少动"
        "light" -> "偶尔运动"
        "moderate" -> "每周运动1-3次"
        "active" -> "每周运动3-5次"
        "daily" -> "几乎每天运动"
        else -> code
    }

    fun dietZh(code: String): String = when (code) {
        "balanced" -> "饮食均衡"
        "vegetarian" -> "偏素食"
        "meat" -> "偏肉食"
        "salty" -> "口味偏咸"
        "oily" -> "偏油腻"
        "sweet" -> "嗜甜"
        else -> code
    }

    fun smokingZh(code: String): String = when (code) {
        "never" -> "从不吸烟"
        "quit" -> "已戒烟"
        "occasional" -> "偶尔吸烟"
        "daily" -> "每天吸烟"
        else -> code
    }

    fun drinkingZh(code: String): String = when (code) {
        "never" -> "从不饮酒"
        "quit" -> "已戒酒"
        "occasional" -> "偶尔饮酒"
        "frequent" -> "经常饮酒"
        else -> code
    }
}

/**
 * 个人健康档案摘要（中文，仅含已填写项）。
 * 供健康问答的三条 LLM 路径拼接上下文：快路径全量摘要、检索路径、Agent 路径。
 */
fun FamilyMember.profileSummary(): String = buildString {
    DateUtils.age(dateOfBirth)?.let { appendLine("年龄：$it 岁") }
    bloodType?.takeIf { it.isNotBlank() }?.let { appendLine("血型：$it 型") }
    val h = heightCm
    val w = weightKg
    if (h != null && h > 0 && w != null && w > 0) {
        val bmi = w / (h / 100.0).pow(2)
        appendLine("身高：${trimNum(h)}cm，体重：${trimNum(w)}kg（BMI ${"%.1f".format(bmi)}）")
    } else {
        h?.takeIf { it > 0 }?.let { appendLine("身高：${trimNum(it)}cm") }
        w?.takeIf { it > 0 }?.let { appendLine("体重：${trimNum(it)}kg") }
    }
    waistCm?.takeIf { it > 0 }?.let { appendLine("腰围：${trimNum(it)}cm") }
    exercise?.let { appendLine("运动习惯：${ProfileCodes.exerciseZh(it)}") }
    diet?.let { appendLine("饮食习惯：${ProfileCodes.dietZh(it)}") }
    smoking?.let { appendLine("吸烟：${ProfileCodes.smokingZh(it)}") }
    drinking?.let { appendLine("饮酒：${ProfileCodes.drinkingZh(it)}") }
    chronicConditions?.trim()?.takeIf { it.isNotBlank() }?.let { appendLine("慢性病史：$it") }
    surgeryHistory?.trim()?.takeIf { it.isNotBlank() }?.let { appendLine("手术史：$it") }
}.trimEnd('\n')

/** 175.0 → 175（去掉无意义的小数位） */
private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
