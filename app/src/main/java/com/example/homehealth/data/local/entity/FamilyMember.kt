package com.example.homehealth.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 家庭成员 */
@Entity(tableName = "family_members")
data class FamilyMember(
    @PrimaryKey val id: String, // UUID
    val name: String,
    val relationship: String, // 关系 code，如 "self", "spouse"（旧数据可能是中文，显示时归一化）
    val avatarUrl: String? = null,
    val dateOfBirth: String? = null, // ISO date: yyyy-MM-dd
    val gender: String? = null, // "male", "female", "other"
    val heightCm: Double? = null, // 身高（厘米）
    val weightKg: Double? = null, // 体重（千克）
    // ---- 健康档案（v10 新增，均可空，问答上下文按需拼接） ----
    val bloodType: String? = null,      // "A" / "B" / "AB" / "O"
    val waistCm: Double? = null,        // 腰围（厘米）
    val exercise: String? = null,       // 运动习惯 code，见 ProfileCodes.EXERCISE
    val diet: String? = null,           // 饮食习惯 code，见 ProfileCodes.DIET
    val smoking: String? = null,        // 吸烟 code，见 ProfileCodes.SMOKING
    val drinking: String? = null,       // 饮酒 code，见 ProfileCodes.DRINKING
    val chronicConditions: String? = null, // 慢性病史（自由文本）
    val surgeryHistory: String? = null,    // 手术史（自由文本）
    val notes: String? = null
)
