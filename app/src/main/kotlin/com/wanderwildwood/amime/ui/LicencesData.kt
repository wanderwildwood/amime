package com.wanderwildwood.amime.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.amime.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/amime"

/** What the APK carries that is not this app's own code, and the licence each project declares. */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("Meshtastic protocol definitions (unchanged)", "Meshtastic LLC", "GPL-3.0", "https://github.com/meshtastic/protobufs"),
    LicenceComponent("MeshCore (protocol implemented from reading its source, none copied)", "the MeshCore authors", "MIT", "https://github.com/meshcore-dev/MeshCore"),
    LicenceComponent("ZXing (QR codes)", "the ZXing authors", "Apache-2.0", "https://github.com/zxing/zxing"),
    LicenceComponent("Wire runtime", "Square", "Apache-2.0", "https://square.github.io/wire/"),
    LicenceComponent("Mudita Mindful Design (MMD)", "Mudita", "Apache-2.0", "https://github.com/mudita/MMD"),
    LicenceComponent("Lato typeface, supplied with MMD", "\u0141ukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("AndroidX and Jetpack Compose", "Google", "Apache-2.0", "https://developer.android.com/jetpack"),
    LicenceComponent("Kotlin and kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://kotlinlang.org"),
    LicenceComponent("Material Symbols icons", "Google", "Apache-2.0", "https://fonts.google.com/icons"),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.gpl_3_0,
    "apache" to R.raw.apache_2_0,
    "mit" to R.raw.mit,
    "ofl" to R.raw.ofl_1_1,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Mesh",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "apache" -> R.string.licences_full_apache
            "mit" -> R.string.licences_full_mit
            "ofl" -> R.string.licences_full_ofl
            else -> R.string.licences_full_head
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    val noText = stringResource(R.string.licences_no_text, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_head),
        noText = { licence -> noText.replace("@@", licence) },
        noBrowser = { address -> none.replace("@@", address) },
    )
}
