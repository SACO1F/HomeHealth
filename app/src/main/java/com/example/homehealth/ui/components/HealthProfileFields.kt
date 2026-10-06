package com.example.homehealth.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.ProfileCodes

/** 编辑对话框与健康档案展示之间传递的生活方式 / 病史信息 */
data class MemberHealthInfo(
    val bloodType: String? = null,
    val waistCm: Double? = null,
    val exercise: String? = null,
    val diet: String? = null,
    val smoking: String? = null,
    val drinking: String? = null,
    val chronicConditions: String? = null,
    val surgeryHistory: String? = null
)

fun FamilyMember.healthInfo(): MemberHealthInfo = MemberHealthInfo(
    bloodType = bloodType,
    waistCm = waistCm,
    exercise = exercise,
    diet = diet,
    smoking = smoking,
    drinking = drinking,
    chronicConditions = chronicConditions,
    surgeryHistory = surgeryHistory
)

fun FamilyMember.withHealthInfo(health: MemberHealthInfo): FamilyMember = copy(
    bloodType = health.bloodType,
    waistCm = health.waistCm,
    exercise = health.exercise,
    diet = health.diet,
    smoking = health.smoking,
    drinking = health.drinking,
    chronicConditions = health.chronicConditions,
    surgeryHistory = health.surgeryHistory
)

@Composable
fun profileUnsetLabel(): String = stringResource(R.string.profile_unset)

@Composable
fun bloodTypeLabel(code: String): String = code

@Composable
fun exerciseLabel(code: String): String = when (code) {
    "sedentary" -> stringResource(R.string.exercise_sedentary)
    "light" -> stringResource(R.string.exercise_light)
    "moderate" -> stringResource(R.string.exercise_moderate)
    "active" -> stringResource(R.string.exercise_active)
    "daily" -> stringResource(R.string.exercise_daily)
    else -> code
}

@Composable
fun dietLabel(code: String): String = when (code) {
    "balanced" -> stringResource(R.string.diet_balanced)
    "vegetarian" -> stringResource(R.string.diet_vegetarian)
    "meat" -> stringResource(R.string.diet_meat)
    "salty" -> stringResource(R.string.diet_salty)
    "oily" -> stringResource(R.string.diet_oily)
    "sweet" -> stringResource(R.string.diet_sweet)
    else -> code
}

@Composable
fun smokingLabel(code: String): String = when (code) {
    "never" -> stringResource(R.string.smoke_never)
    "quit" -> stringResource(R.string.smoke_quit)
    "occasional" -> stringResource(R.string.smoke_occasional)
    "daily" -> stringResource(R.string.smoke_daily)
    else -> code
}

@Composable
fun drinkingLabel(code: String): String = when (code) {
    "never" -> stringResource(R.string.drink_never)
    "quit" -> stringResource(R.string.drink_quit)
    "occasional" -> stringResource(R.string.drink_occasional)
    "frequent" -> stringResource(R.string.drink_frequent)
    else -> code
}

/** 生活方式 / 病史选项（code → 本地化文案）；血型直接显示 code 本身 */
@Composable
fun profileOptionLabel(kind: ProfileKind, code: String): String = when (kind) {
    ProfileKind.BLOOD_TYPE -> bloodTypeLabel(code)
    ProfileKind.EXERCISE -> exerciseLabel(code)
    ProfileKind.DIET -> dietLabel(code)
    ProfileKind.SMOKING -> smokingLabel(code)
    ProfileKind.DRINKING -> drinkingLabel(code)
}

enum class ProfileKind(val codes: List<String>) {
    BLOOD_TYPE(ProfileCodes.BLOOD_TYPES),
    EXERCISE(ProfileCodes.EXERCISE),
    DIET(ProfileCodes.DIET),
    SMOKING(ProfileCodes.SMOKING),
    DRINKING(ProfileCodes.DRINKING)
}
