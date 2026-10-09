package app.vela.ui.settings.sections

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.vela.R
import app.vela.device.DeviceCalls
import app.vela.device.DeviceLinkPrefs
import app.vela.device.DeviceMediaListener
import app.vela.device.DeviceQrScanner
import app.vela.device.DeviceService
import app.vela.ui.VelaDialog
import app.vela.ui.dpadHighlight
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.Hint
import app.vela.ui.settings.PageIntro
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow
import app.vela.ui.settings.settingsAnchor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Nav device: pair a Bluetooth display, see whether it is connected, and set what it shows. Every
 * permission is asked for here, when the thing that needs it is turned on.
 */
@Composable
internal fun DeviceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val bond = DeviceLinkPrefs.bond.value
    var scanning by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    // Nearby devices, then the link. [fresh] is carried across the permission prompt.
    var startFresh by remember { mutableStateOf(false) }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) DeviceService.start(context, fresh = startFresh)
    }
    fun connect(fresh: Boolean) {
        if (DeviceService.hasBluetooth(context)) {
            DeviceService.start(context, fresh)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startFresh = fresh
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true
    }
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // The call state is the one the feature cannot work without; the rest only add detail.
        if (result[Manifest.permission.READ_PHONE_STATE] == true) {
            DeviceLinkPrefs.setCalls(context, true)
            DeviceService.refresh(context)
        }
    }

    SettingsScaffold(stringResource(R.string.settings_device), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PageIntro(stringResource(R.string.device_intro))
        if (bond == null) {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.device_none), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.device_pair_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Button(
                        modifier = topRow.padding(top = 10.dp).dpadHighlight(ButtonDefaults.shape),
                        onClick = {
                            if (granted(context, Manifest.permission.CAMERA)) scanning = true
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                    ) { Text(stringResource(R.string.device_pair)) }
                }
            }
        } else {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        bond.name.ifBlank { stringResource(R.string.settings_device) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(DeviceService.linkState.value.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.device_connect),
                    checked = DeviceLinkPrefs.enabled.value,
                    onCheckedChange = { on ->
                        DeviceLinkPrefs.setEnabled(context, on)
                        if (on) connect(fresh = false) else DeviceService.stop(context)
                    },
                    hint = stringResource(R.string.device_connect_hint),
                    switchModifier = topRow,
                )
            }
            SettingsGroup(stringResource(R.string.device_display)) {
                BrightnessRow()
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.device_minimal),
                    checked = DeviceLinkPrefs.minimal.value,
                    onCheckedChange = {
                        DeviceLinkPrefs.setMinimal(context, it)
                        DeviceService.pushMinimal(context)
                    },
                    hint = stringResource(R.string.device_minimal_hint),
                )
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.device_dark_map),
                    checked = DeviceLinkPrefs.darkMap.value,
                    onCheckedChange = {
                        DeviceLinkPrefs.setDarkMap(context, it)
                        DeviceService.pushTheme(context)
                    },
                )
            }
            SettingsGroup(stringResource(R.string.device_also_show)) {
                ToggleRow(
                    label = stringResource(R.string.device_media),
                    checked = DeviceLinkPrefs.media.value,
                    onCheckedChange = { on ->
                        DeviceLinkPrefs.setMedia(context, on)
                        DeviceService.refresh(context)
                        // Reading what is playing is granted on a system page, not by a prompt.
                        if (on && !DeviceMediaListener.hasAccess(context)) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                    hint = stringResource(R.string.device_media_hint),
                )
                GroupDivider()
                ToggleRow(
                    label = stringResource(R.string.device_calls),
                    checked = DeviceLinkPrefs.calls.value,
                    onCheckedChange = { on ->
                        if (!on) {
                            DeviceLinkPrefs.setCalls(context, false)
                            DeviceService.refresh(context)
                        } else if (granted(context, Manifest.permission.READ_PHONE_STATE)) {
                            DeviceLinkPrefs.setCalls(context, true)
                            DeviceService.refresh(context)
                        } else {
                            callPermissions.launch(DeviceCalls.PERMISSIONS)
                        }
                    },
                    hint = stringResource(R.string.device_calls_hint),
                )
            }
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        enabled = DeviceService.linkState.value == DeviceService.Link.CONNECTED,
                        modifier = Modifier.dpadHighlight(ButtonDefaults.filledTonalShape),
                        onClick = { DeviceService.sleep(context) },
                    ) { Text(stringResource(R.string.device_sleep)) }
                    FilledTonalButton(
                        modifier = Modifier.dpadHighlight(ButtonDefaults.filledTonalShape),
                        onClick = { confirmUnpair = true },
                    ) { Text(stringResource(R.string.device_unpair)) }
                }
                Hint(stringResource(R.string.device_sleep_hint))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (scanning) {
        DeviceQrScanner(
            onBond = { b ->
                scanning = false
                DeviceLinkPrefs.saveBond(context, b)
                DeviceLinkPrefs.setEnabled(context, true)
                connect(fresh = true)
            },
            onDismiss = { scanning = false },
        )
    }
    if (confirmUnpair) {
        VelaDialog(
            onDismissRequest = { confirmUnpair = false },
            title = stringResource(R.string.device_unpair_title),
            confirmText = stringResource(R.string.device_unpair),
            onConfirm = {
                confirmUnpair = false
                DeviceService.unpair(context)
            },
            dismissText = stringResource(android.R.string.cancel),
            onDismiss = { confirmUnpair = false },
        ) {
            Text(stringResource(R.string.device_unpair_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The device's backlight. The device keeps a floor of 5 percent, so the slider starts there. */
@Composable
private fun BrightnessRow() {
    val context = LocalContext.current
    val label = stringResource(R.string.device_brightness)
    var value by remember { mutableFloatStateOf(DeviceLinkPrefs.brightness.intValue.toFloat()) }
    // The last value written to the device: a drag sends a step now and then, not every pixel.
    var pushed by remember { mutableFloatStateOf(value) }
    fun push() {
        pushed = value
        DeviceLinkPrefs.setBrightness(context, value.roundToInt())
        DeviceService.pushBrightness(context)
    }
    Column(Modifier.fillMaxWidth().settingsAnchor(label).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.device_brightness_value, value.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = {
                value = it
                if (abs(it - pushed) >= 5f) push()
            },
            onValueChangeFinished = { push() },
            valueRange = 5f..100f,
            modifier = Modifier.fillMaxWidth().dpadHighlight(),
        )
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
