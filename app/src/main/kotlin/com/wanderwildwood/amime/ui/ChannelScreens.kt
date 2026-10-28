package com.wanderwildwood.amime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.R
import com.wanderwildwood.amime.link.RadioKind
import com.wanderwildwood.amime.meshtastic.CHANNEL_NAME_BYTES
import com.wanderwildwood.amime.meshtastic.ChannelKeys
import com.wanderwildwood.amime.meshtastic.ImportItem
import com.wanderwildwood.amime.meshtastic.ImportPlan
import com.wanderwildwood.amime.meshtastic.KeyKind
import com.wanderwildwood.amime.meshtastic.MtChannelInfo
import com.wanderwildwood.amime.meshtastic.MtSession
import okio.ByteString

@Composable
internal fun keyKindText(kind: KeyKind): String = stringResource(
    when (kind) {
        KeyKind.NONE -> R.string.mtx_key_none
        KeyKind.DEFAULT -> R.string.mtx_key_default
        KeyKind.DEFAULT_VARIANT -> R.string.mtx_key_variant
        KeyKind.OWN_128 -> R.string.mtx_key_128
        KeyKind.OWN_256 -> R.string.mtx_key_256
        KeyKind.ODD -> R.string.mtx_key_odd
    },
)

/** A button of the house kind: outlined, 48 dp tall. */
@Composable
internal fun HouseButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButtonMMD(onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp)) {
        TextMMD(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

/** A bordered row with a title and an optional second line. */
@Composable
internal fun BorderedRow(title: String, line: String?, settled: Boolean = true, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .stateBorder(settled = settled)
            .let { if (onClick != null) it.clickableTarget(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
        if (line != null) TextMMD(text = line, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun Note(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * The radio's eight channel slots, and the ways to put a channel in one: type it, scan a
 * link with the Camera app, or paste one. The other direction is a QR code of the lot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    channels: List<MtChannelInfo>,
    primaryName: String,
    notice: String?,
    onEdit: (MtChannelInfo) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onShow: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val ready = channels.isNotEmpty()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { BarTitle(stringResource(R.string.mtx_channels_title), RadioKind.MESHTASTIC) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.conversation_back), onClose) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            notice?.let { Note(it) }
            PagedColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                item { Note(stringResource(R.string.mtx_channels_note)) }
                if (!ready) item { Note(stringResource(R.string.mtx_not_ready)) }
                items(channels.size) { i ->
                    val c = channels[i]
                    val name = c.name.ifEmpty { if (c.primary) primaryName else "Channel ${c.index}" }
                    if (!c.enabled) {
                        BorderedRow(
                            title = "${c.index + 1}",
                            line = stringResource(R.string.mtx_slot_free),
                            settled = false,
                        )
                    } else {
                        BorderedRow(
                            title = name,
                            line = stringResource(
                                R.string.mtx_channel_line,
                                stringResource(if (c.primary) R.string.mtx_role_primary else R.string.mtx_role_secondary),
                                keyKindText(c.key),
                            ),
                            onClick = { onEdit(c) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HouseButton(stringResource(R.string.mtx_add), Modifier.weight(1f), ready, onAdd)
                HouseButton(stringResource(R.string.mtx_show), Modifier.weight(1f), ready, onShow)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HouseButton(stringResource(R.string.mtx_scan), Modifier.weight(1f), ready, onScan)
                HouseButton(stringResource(R.string.mtx_paste), Modifier.weight(1f), ready, onPaste)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private enum class KeyChoice { KEEP, NONE, DEFAULT, RANDOM, PASTE }

/**
 * Adding a channel, or changing one.
 *
 * The key is never shown: a channel's key is its password. A new key is made here, kept only
 * until Save, and shared afterwards as a QR code rather than read out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelEditScreen(
    existing: MtChannelInfo?,
    slot: Int,
    primaryName: String,
    newKey: () -> ByteString,
    onSave: (name: String, key: ByteString?, uplink: Boolean, downlink: Boolean) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var name by remember { mutableStateOf(existing?.name?.ifEmpty { if (existing.primary) primaryName else "" } ?: "") }
    var choice by remember { mutableStateOf(if (existing != null) KeyChoice.KEEP else KeyChoice.RANDOM) }
    var pasted by remember { mutableStateOf("") }
    val random = remember { newKey() }
    var uplink by remember { mutableStateOf(existing?.uplink ?: false) }
    var downlink by remember { mutableStateOf(existing?.downlink ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val pastedKey = if (choice == KeyChoice.PASTE) ChannelKeys.fromText(pasted) else null
    val keyOk = choice != KeyChoice.PASTE || (pasted.isNotBlank() && pastedKey != null)
    val nameOk = name.toByteArray().size <= CHANNEL_NAME_BYTES && (existing?.primary == true || name.isNotBlank())
    val key: ByteString? = when (choice) {
        KeyChoice.KEEP -> null
        KeyChoice.NONE -> ChannelKeys.NONE
        KeyChoice.DEFAULT -> ChannelKeys.DEFAULT
        KeyChoice.RANDOM -> random
        KeyChoice.PASTE -> pastedKey
    }
    // The main channel's name is blank for the preset's own; saving the shown preset name back
    // would turn the default channel into a custom one called LongFast.
    val savedName = if (existing?.primary == true && name == primaryName && existing.name.isEmpty()) "" else name

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(text = stringResource(if (existing == null) R.string.mtx_new_title else R.string.mtx_edit_title))
                },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.conversation_back), onClose) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PagedColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                item {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        TextMMD(text = stringResource(R.string.mtx_name_label), style = MaterialTheme.typography.labelSmall)
                        TextFieldMMD(
                            value = name, onValueChange = { name = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    TextMMD(
                        text = stringResource(R.string.mtx_key_label),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                val choices = buildList {
                    if (existing != null) add(KeyChoice.KEEP to R.string.mtx_key_keep)
                    add(KeyChoice.RANDOM to R.string.mtx_key_choose_random)
                    add(KeyChoice.PASTE to R.string.mtx_key_choose_paste)
                    add(KeyChoice.DEFAULT to R.string.mtx_key_choose_default)
                    add(KeyChoice.NONE to R.string.mtx_key_choose_none)
                }
                items(choices.size) { i ->
                    val (c, label) = choices[i]
                    BorderedRow(stringResource(label), null, settled = choice == c) { choice = c }
                }
                if (choice == KeyChoice.PASTE) {
                    item {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            TextMMD(text = stringResource(R.string.mtx_key_paste_label), style = MaterialTheme.typography.labelSmall)
                            TextFieldMMD(
                                value = pasted, onValueChange = { pasted = it }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (pasted.isNotBlank() && pastedKey == null) {
                                TextMMD(text = stringResource(R.string.mtx_key_paste_bad), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                item {
                    BorderedRow(
                        stringResource(R.string.mtx_uplink),
                        stringResource(if (uplink) R.string.mtx_on else R.string.mtx_off),
                    ) { uplink = !uplink }
                }
                item {
                    BorderedRow(
                        stringResource(R.string.mtx_downlink),
                        stringResource(if (downlink) R.string.mtx_on else R.string.mtx_off),
                    ) { downlink = !downlink }
                }
                item {
                    Note(
                        if (existing == null) stringResource(R.string.mtx_new_effect, slot + 1)
                        else stringResource(R.string.mtx_edit_effect, existing.index + 1),
                    )
                }
                if (existing?.primary == true) item { Note(stringResource(R.string.mtx_primary_warn)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (existing != null && !existing.primary) {
                    HouseButton(stringResource(R.string.mtx_delete), Modifier.weight(1f)) { confirmDelete = true }
                }
                HouseButton(stringResource(R.string.mtx_save), Modifier.weight(1f), nameOk && keyOk) {
                    onSave(savedName, key, uplink, downlink)
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        EInkDialog(onDismiss = { confirmDelete = false }) {
            TextMMD(
                text = stringResource(R.string.mtx_delete_confirm, existing.name.ifEmpty { "${existing.index + 1}" }),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HouseButton(stringResource(R.string.login_cancel), Modifier.weight(1f)) { confirmDelete = false }
                HouseButton(stringResource(R.string.mtx_delete_do), Modifier.weight(1f)) {
                    confirmDelete = false
                    onDelete()
                }
            }
        }
    }
}

@Composable
private fun importState(item: ImportItem): String = stringResource(
    when (item.state) {
        ImportItem.State.ADD -> R.string.mtx_import_add
        ImportItem.State.PRESENT -> R.string.mtx_import_present
        ImportItem.State.NO_ROOM -> R.string.mtx_import_noroom
        ImportItem.State.REFUSED -> R.string.mtx_import_refused
    },
)

/**
 * What a channel link would do, before it does anything. Adding the channels and changing
 * the radio's own settings are two separate buttons, the second behind its own confirmation,
 * because the second one can take this radio off the frequency everyone else is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelImportScreen(
    plan: ImportPlan,
    regionName: String,
    onAdd: () -> Unit,
    onApplyRadio: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var confirmRadio by remember { mutableStateOf(false) }
    val preset = plan.lora?.modem_preset?.let { MtSession.presetLabel(it) } ?: ""
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.mtx_import_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.conversation_back), onClose) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PagedColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(plan.items.size) { i ->
                    val item = plan.items[i]
                    BorderedRow(
                        title = item.name,
                        line = stringResource(R.string.mtx_import_item, keyKindText(item.key), importState(item)),
                        settled = item.state == ImportItem.State.ADD,
                    )
                }
                if (plan.toAdd == 0) item { Note(stringResource(R.string.mtx_import_nothing)) }
                plan.lora?.let {
                    item { Note(stringResource(R.string.mtx_import_lora, regionName, preset)) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (plan.lora != null) {
                    HouseButton(stringResource(R.string.mtx_import_lora_do), Modifier.weight(1f)) { confirmRadio = true }
                }
                HouseButton(stringResource(R.string.mtx_import_do, plan.toAdd), Modifier.weight(1f), plan.toAdd > 0, onAdd)
            }
        }
    }
    if (confirmRadio) {
        EInkDialog(onDismiss = { confirmRadio = false }) {
            TextMMD(
                text = stringResource(R.string.mtx_import_lora_confirm, regionName, preset),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HouseButton(stringResource(R.string.login_cancel), Modifier.weight(1f)) { confirmRadio = false }
                HouseButton(stringResource(R.string.mtx_import_lora_yes), Modifier.weight(1f)) {
                    confirmRadio = false
                    onApplyRadio()
                }
            }
        }
    }
}

/** Typing or pasting a channel link, for when there is no camera to hand. */
@Composable
fun PasteLinkDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.mtx_paste_title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        TextMMD(text = stringResource(R.string.mtx_paste_label), style = MaterialTheme.typography.labelSmall)
        TextFieldMMD(
            value = text, onValueChange = { text = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HouseButton(stringResource(R.string.login_cancel), Modifier.weight(1f), onClick = onDismiss)
            HouseButton(stringResource(R.string.mtx_paste_go), Modifier.weight(1f), text.isNotBlank()) { onRead(text) }
        }
    }
}

/** The radio's channels as a QR code to scan from another phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelQrScreen(
    linkWith: String?,
    linkWithout: String?,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var withRadio by remember { mutableStateOf(true) }
    val link = if (withRadio) linkWith else linkWithout
    val fits = link != null && qrModules(link) != null
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.mtx_qr_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.conversation_back), onClose) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                link == null -> Note(stringResource(R.string.mtx_qr_empty))
                !fits -> Note(stringResource(R.string.mtx_qr_toolong))
                else -> QrCode(link, Modifier.padding(8.dp))
            }
            Note(stringResource(R.string.mtx_qr_warn))
            BorderedRow(
                stringResource(R.string.mtx_qr_settings, stringResource(if (withRadio) R.string.mtx_yes else R.string.mtx_no)),
                stringResource(R.string.mtx_qr_settings_help),
            ) { withRadio = !withRadio }
        }
    }
}
