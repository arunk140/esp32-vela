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
import app.vela.device.watch.WatchPrefs
import app.vela.device.watch.WatchProbe
import app.vela.device.watch.WatchService
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
                // Answering a call in another app opens that app's call screen. With the phone
                // locked, Android only lets Vela do that if it may display over other apps.
                var mayOpen by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                    mayOpen = Settings.canDrawOverlays(context)
                    onPauseOrDispose { }
                }
                if (DeviceLinkPrefs.calls.value && !mayOpen) {
                    GroupDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.device_calls_locked), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.device_calls_locked_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        FilledTonalButton(
                            modifier = Modifier.padding(top = 8.dp).dpadHighlight(ButtonDefaults.filledTonalShape),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) { Text(stringResource(R.string.device_calls_locked_action)) }
                    }
                }
                GroupDivider()
                // Works with the switch off too: it shows what the device does with a call.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    FilledTonalButton(
                        enabled = DeviceService.linkState.value == DeviceService.Link.CONNECTED,
                        modifier = Modifier.dpadHighlight(ButtonDefaults.filledTonalShape),
                        onClick = { DeviceService.simulateCall(context) },
                    ) { Text(stringResource(R.string.device_simulate_call)) }
                    Text(
                        stringResource(R.string.device_simulate_call_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
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
        WatchTestSection()
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

/**
 * Test of the CMF watch's app channel ([WatchProbe]): a second Bluetooth client next to Nothing X,
 * the channel key, a PING, and a made-up drive for the watch's Vela Nav app. Independent of the
 * paired nav device above.
 */
@Composable
private fun WatchTestSection() {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) WatchProbe.linkTest(context)
    }
    val servicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) WatchService.start(context)
    }
    var picking by remember { mutableStateOf(false) }
    remember { WatchPrefs.load(context) }
    val chosen = WatchPrefs.address.value
    var candidates by remember { mutableStateOf(emptyList<WatchProbe.Candidate>()) }
    val listPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            candidates = WatchProbe.candidates(context)
            picking = true
        }
    }
    SettingsGroup(stringResource(R.string.watch_test)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                stringResource(R.string.watch_test_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val name = chosen?.let { a -> candidates.firstOrNull { it.address == a }?.name }
            Text(
                if (chosen == null) stringResource(R.string.watch_test_none)
                else stringResource(R.string.watch_test_picked, name ?: stringResource(R.string.watch_test_saved)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            androidx.compose.material3.TextButton(
                enabled = !WatchProbe.running.value,
                onClick = {
                    if (DeviceService.hasBluetooth(context)) {
                        candidates = WatchProbe.candidates(context)
                        picking = true
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        listPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
            ) { Text(stringResource(R.string.watch_test_pick)) }
            if (picking) {
                if (candidates.isEmpty()) Text(stringResource(R.string.watch_test_no_bonded))
                candidates.forEach { c ->
                    androidx.compose.material3.OutlinedButton(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        onClick = {
                            WatchProbe.close()
                            WatchPrefs.setAddress(context, c.address)
                            picking = false
                            // a running link moves to the new watch
                            WatchService.stop(context)
                            WatchService.start(context)
                        },
                    ) {
                        Text(c.name + if (c.connected) "  ·  " + context.getString(R.string.watch_test_connected) else "")
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    enabled = !WatchProbe.running.value && chosen != null,
                    modifier = Modifier.dpadHighlight(ButtonDefaults.filledTonalShape),
                    onClick = {
                        if (DeviceService.hasBluetooth(context)) WatchProbe.linkTest(context)
                        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    },
                ) { Text(stringResource(R.string.watch_test_run)) }
                FilledTonalButton(
                    // the live link numbers its own messages: a demo between them would make gaps
                    enabled = WatchProbe.linked.value && !WatchProbe.running.value && !WatchPrefs.enabled.value,
                    modifier = Modifier.dpadHighlight(ButtonDefaults.filledTonalShape),
                    onClick = { WatchProbe.demoDrive() },
                ) { Text(stringResource(R.string.watch_test_demo)) }
            }
            if (WatchProbe.linked.value && !WatchProbe.running.value) {
                androidx.compose.material3.TextButton(onClick = { WatchProbe.close() }) {
                    Text(stringResource(R.string.watch_test_close))
                }
            }
            WatchProbe.log.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.watch_show_nav),
            checked = WatchPrefs.enabled.value,
            onCheckedChange = { on ->
                if (!on) {
                    WatchPrefs.setEnabled(context, false)
                    WatchService.stop(context)
                } else if (chosen != null) {
                    WatchProbe.close()
                    WatchPrefs.setEnabled(context, true)
                    if (DeviceService.hasBluetooth(context)) WatchService.start(context)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        servicePermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
            },
            hint = if (WatchPrefs.enabled.value) stringResource(WatchService.state.value.label)
            else stringResource(R.string.watch_show_nav_hint),
        )
    }
}
