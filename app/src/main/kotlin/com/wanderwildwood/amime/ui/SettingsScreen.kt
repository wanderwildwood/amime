package com.wanderwildwood.amime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.amime.R
import com.wanderwildwood.amime.mesh.GeoPoint
import com.wanderwildwood.amime.meshtastic.Field
import com.wanderwildwood.amime.meshtastic.Kind
import com.wanderwildwood.amime.meshtastic.MtConfigs
import com.wanderwildwood.amime.meshtastic.MtFields
import com.wanderwildwood.amime.meshtastic.Part

/**
 * The radio's settings, in sections. Every row opens a dialog for its one value, and the
 * dialog says what the change does before it is made. The rows come from the table in
 * [MtFields]; this screen knows nothing about any one setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsScreen(
    configs: MtConfigs?,
    regionLabel: String,
    typedPosition: GeoPoint?,
    onRegion: () -> Unit,
    onChange: (Field, Any) -> MtFields.Applied,
    onFixedPosition: () -> Boolean,
    onClearFixed: () -> Boolean,
    onClose: () -> Unit,
) {
    var part by remember { mutableStateOf<Part?>(null) }
    var editing by remember { mutableStateOf<Field?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    BackHandler {
        if (part != null) {
            part = null
            notice = null
        } else onClose()
    }

    val notRead = stringResource(R.string.mts_not_read)
    val sentRestart = stringResource(R.string.mts_sent_restart, "%s")
    val sentNow = stringResource(R.string.mts_sent_now, "%s")
    val fixedSent = stringResource(R.string.mts_fixed_sent)
    val refusedPrefix = stringResource(R.string.mts_refused_prefix, "%s")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = part?.title ?: stringResource(R.string.mts_title)) },
                navigationIcon = {
                    BarButton(Icons.Close, stringResource(R.string.conversation_back)) {
                        if (part != null) {
                            part = null
                            notice = null
                        } else onClose()
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            notice?.let { Note(it) }
            if (configs == null) {
                Note(stringResource(R.string.mts_wait))
                return@Column
            }
            val current = part
            if (current == null) {
                PagedColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item { BorderedRow(stringResource(R.string.mts_region_row), regionLabel, onClick = onRegion) }
                    items(Part.entries.size) { i ->
                        val p = Part.entries[i]
                        val read = configs.has(p)
                        BorderedRow(
                            title = p.title,
                            line = if (read) null else notRead,
                            settled = read,
                            onClick = if (read) ({ part = p; notice = null }) else null,
                        )
                    }
                    item { Note(stringResource(R.string.mts_firmware_note)) }
                }
            } else {
                val fields = MtFields.inPart(current)
                PagedColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(fields.size) { i ->
                        val f = fields[i]
                        BorderedRow(
                            title = f.title,
                            line = MtFields.display(f, configs) ?: notRead,
                            onClick = if (MtFields.editable(f)) ({ editing = f }) else null,
                        )
                    }
                    if (current == Part.POSITION) {
                        item {
                            BorderedRow(
                                stringResource(R.string.mts_fixed_set),
                                stringResource(R.string.mts_fixed_set_help),
                                settled = typedPosition != null,
                                onClick = if (typedPosition != null) ({
                                    notice = if (onFixedPosition()) fixedSent else notRead
                                }) else null,
                            )
                        }
                        item {
                            BorderedRow(
                                stringResource(R.string.mts_fixed_clear), null,
                                onClick = {
                                    notice = if (onClearFixed()) fixedSent else notRead
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { f ->
        if (configs != null) {
            FieldDialog(
                field = f,
                configs = configs,
                onDismiss = { editing = null },
                onSet = { value ->
                    editing = null
                    notice = when (val r = onChange(f, value)) {
                        MtFields.Applied.NotRead -> notRead
                        is MtFields.Applied.Refused -> refusedPrefix.replace("%s", r.reason)
                        is MtFields.Applied.Change -> {
                            val shown = "${f.title} = ${MtFields.display(f, r.configs)}"
                            (if (f.restarts) sentRestart else sentNow).replace("%s", shown)
                        }
                    }
                },
            )
        }
    }
}

/** The dialog for one setting: its words, what it does, and the way to give it a new value. */
@Composable
private fun FieldDialog(field: Field, configs: MtConfigs, onDismiss: () -> Unit, onSet: (Any) -> Unit) {
    val kind = field.kind
    val start: Any = field.read(configs) ?: when (kind) {
        is Kind.Choice -> kind.options.first().value
        is Kind.Num -> kind.min
        Kind.Flag -> false
        is Kind.Text, Kind.Info -> ""
    }
    var choice by remember { mutableStateOf(start as? Int ?: 0) }
    var flag by remember { mutableStateOf(start as? Boolean ?: false) }
    var text by remember { mutableStateOf(if (kind is Kind.Num) start.toString() else start as? String ?: "") }

    val value: Any? = when (kind) {
        is Kind.Choice -> choice
        is Kind.Num -> text.trim().toIntOrNull()
        Kind.Flag -> flag
        is Kind.Text -> text
        Kind.Info -> null
    }
    val refusal = value?.let { MtFields.refusal(field, it, configs) }
    val optionWarn = (kind as? Kind.Choice)?.options?.firstOrNull { it.value == choice }?.warn
    val warn = optionWarn ?: field.warn

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = field.title, style = MaterialTheme.typography.bodyLarge)
        field.help?.let {
            Spacer(Modifier.height(6.dp))
            TextMMD(text = it, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))
        when (kind) {
            is Kind.Choice -> {
                PagedColumn(modifier = Modifier.heightIn(max = 300.dp).fillMaxWidth()) {
                    items(kind.options.size) { i ->
                        val o = kind.options[i]
                        BorderedRow(o.label, null, settled = o.value == choice) { choice = o.value }
                    }
                }
                kind.options.firstOrNull { it.value == choice }?.note?.let {
                    Spacer(Modifier.height(6.dp))
                    TextMMD(text = it, style = MaterialTheme.typography.labelSmall)
                }
            }
            Kind.Flag -> {
                BorderedRow(stringResource(R.string.mtx_on), null, settled = flag) { flag = true }
                BorderedRow(stringResource(R.string.mtx_off), null, settled = !flag) { flag = false }
            }
            is Kind.Num -> {
                TextFieldMMD(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                val unit = if (kind.unit.isEmpty()) "" else " ${kind.unit}"
                TextMMD(text = stringResource(R.string.mts_range, kind.min, kind.max, unit), style = MaterialTheme.typography.labelSmall)
                kind.zero?.let { TextMMD(text = stringResource(R.string.mts_zero_means, it), style = MaterialTheme.typography.labelSmall) }
                if (field.id == "lora.tx_power") {
                    val cap = MtFields.powerCap(configs)
                    TextMMD(
                        text = if (cap != null) stringResource(R.string.mts_cap, cap) else stringResource(R.string.mts_cap_unknown),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            is Kind.Text -> TextFieldMMD(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Kind.Info -> Unit
        }
        warn?.let {
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.mts_careful, it), style = MaterialTheme.typography.labelSmall)
        }
        if (refusal != null && (kind !is Kind.Num || text.isNotBlank())) {
            Spacer(Modifier.height(6.dp))
            TextMMD(text = refusal, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
        TextMMD(
            text = stringResource(if (field.restarts) R.string.mts_restart else R.string.mts_no_restart),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HouseButton(stringResource(R.string.login_cancel), Modifier.weight(1f), onClick = onDismiss)
            HouseButton(stringResource(R.string.mts_set), Modifier.weight(1f), value != null && refusal == null) {
                value?.let(onSet)
            }
        }
    }
}
