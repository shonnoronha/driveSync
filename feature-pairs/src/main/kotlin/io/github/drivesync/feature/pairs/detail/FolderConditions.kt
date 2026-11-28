package io.github.drivesync.feature.pairs.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RunConditions
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsSectionHeader
import io.github.drivesync.core.ui.components.SettingsSwitchRow
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.feature.pairs.R

/** "Automatic syncs" of the folder screen (FR-P4): this folder's own Wi-Fi and charging rules. */
@Composable
internal fun ConditionsSection(
    conditions: RunConditions,
    onEdit: ((PairSettings) -> PairSettings) -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.detail_conditions))
    SettingsGroup {
        item {
            SettingsSwitchRow(
                stringResource(R.string.detail_wifi_only),
                conditions.wifiOnly,
                { on -> onEdit { it.copy(conditions = it.conditions.copy(wifiOnly = on)) } },
                subtitle = stringResource(R.string.detail_wifi_only_body),
                icon = Sym.Wifi,
            )
        }
        item {
            SettingsSwitchRow(
                stringResource(R.string.detail_charging_only),
                conditions.chargingOnly,
                { on -> onEdit { it.copy(conditions = it.conditions.copy(chargingOnly = on)) } },
                subtitle = stringResource(R.string.detail_charging_only_body),
                icon = Sym.Battery,
            )
        }
    }
}
