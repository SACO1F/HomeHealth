package com.example.homehealth.ui.screens.consent

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.homehealth.R

/**
 * 首次启动的「隐私与免责说明」：**必须主动同意**，不同意则退出应用。
 *
 * 为什么要有这一屏 —— 而不只是像以前那样在设置页放一段文字：
 * 本应用处理的是家庭成员的健康记录，属于敏感个人信息。用户有权在**录入任何数据之前**
 * 就知道四件事：数据存在哪里、什么时候会离开这台设备、卸载后会发生什么、
 * 以及这些结论不能当诊断用。
 *
 * 说明内容的版本由 [com.example.homehealth.data.SettingsPrefs.CONSENT_VERSION_CURRENT]
 * 控制：文案有实质变更时递增，老用户会重新看到这一屏。
 */
@Composable
fun ConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.consent_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.consent_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            // 文案比一屏长，必须可滚动：小屏 / 大字体设置下否则会看不到同意按钮
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ConsentSection(R.string.consent_local_title, R.string.consent_local_body)
                ConsentSection(R.string.consent_network_title, R.string.consent_network_body)
                ConsentSection(R.string.consent_uninstall_title, R.string.consent_uninstall_body)
                ConsentSection(R.string.consent_medical_title, R.string.consent_medical_body)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.consent_decline))
                }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.consent_accept))
                }
            }
        }
    }
}

@Composable
private fun ConsentSection(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
    Column {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
