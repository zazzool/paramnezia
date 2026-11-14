package net.paramnezia.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * The app's icons: Lucide (https://lucide.dev, ISC License; portions MIT, Feather by Cole Bemis),
 * outlined, 2-unit stroke with round caps and joins on a 24-unit grid. One consistent, modern set
 * for both apps instead of a mix of filled Material icons. Generated from lucide-static 0.460.0
 * (scratch script lucide2kt.py: SVG circles, rects and lines become path data).
 *
 * ISC License. Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of
 * Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2022.
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without
 * fee is hereby granted, provided that the above copyright notice and this permission notice appear
 * in all copies. THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES.
 */

object PnIcons {
    /** Raw path data, for glyphs drawn on a Canvas (the power button, the orbit segments). */
    val paths: Map<String, List<String>> = mapOf(
        "Warning" to listOf("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01"), // triangle-alert
        "Info" to listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 16v-4", "M12 8h.01"), // info
        "CheckCircle" to listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "m9 12 2 2 4-4"), // circle-check
        "Refresh" to listOf("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5"), // refresh-cw
        "Check" to listOf("M20 6 9 17l-5-5"), // check
        "Share" to listOf("M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M3 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M8.59 13.51L15.42 17.49", "M15.41 6.51L8.59 10.49"), // share-2
        "ChevronUp" to listOf("m18 15-6-6-6 6"), // chevron-up
        "ChevronDown" to listOf("m6 9 6 6 6-6"), // chevron-down
        "ChevronRight" to listOf("m9 18 6-6-6-6"), // chevron-right
        "ArrowLeft" to listOf("m12 19-7-7 7-7", "M19 12H5"), // arrow-left
        "ArrowRight" to listOf("M5 12h14", "m12 5 7 7-7 7"), // arrow-right
        "Close" to listOf("M18 6 6 18", "m6 6 12 12"), // x
        "Plus" to listOf("M5 12h14", "M12 5v14"), // plus
        "Settings" to listOf("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"), // settings
        "MoreVertical" to listOf("M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"), // ellipsis-vertical
        "MapPin" to listOf("M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0", "M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"), // map-pin
        "Paste" to listOf("M15 2H9a1 1 0 0 0-1 1v2c0 .6.4 1 1 1h6c.6 0 1-.4 1-1V3c0-.6-.4-1-1-1Z", "M8 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2M16 4h2a2 2 0 0 1 2 2v2M11 14h10", "m17 10 4 4-4 4"), // clipboard-paste
        "Keyboard" to listOf("M10 8h.01", "M12 12h.01", "M14 8h.01", "M16 12h.01", "M18 8h.01", "M6 8h.01", "M7 16h10", "M8 12h.01", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z"), // keyboard
        "Qr" to listOf("M4 3h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M17 3h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M4 16h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M21 16h-3a2 2 0 0 0-2 2v3", "M21 21v.01", "M12 7v3a2 2 0 0 1-2 2H7", "M3 12h.01", "M12 3h.01", "M12 16v.01", "M16 12h1", "M21 12v.01", "M12 21v-1"), // qr-code
        "File" to listOf("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8"), // file-text
        "Cloud" to listOf("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"), // cloud
        "Server" to listOf("M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z", "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z", "M6 6L6.01 6", "M6 18L6.01 18"), // server
        "PersonAdd" to listOf("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M19 8L19 14", "M22 11L16 11"), // user-plus
        "Link" to listOf("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"), // link
        "Globe" to listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20"), // globe
        "Phone" to listOf("M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2Z", "M12 18h.01"), // smartphone
        "Key" to listOf("M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z", "M16 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0"), // key-round
        "Eye" to listOf("M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"), // eye
        "Cpu" to listOf("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z", "M10 9h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z", "M15 2v2", "M15 20v2", "M2 15h2", "M2 9h2", "M20 15h2", "M20 9h2", "M9 2v2", "M9 20v2"), // cpu
        "Ram" to listOf("M6 19v-3", "M10 19v-3", "M14 19v-3", "M18 19v-3", "M8 11V9", "M16 11V9", "M12 11V9", "M2 15h20", "M2 7a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v1.1a2 2 0 0 0 0 3.837V17a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5.1a2 2 0 0 0 0-3.837Z"), // memory-stick
        "Disk" to listOf("M22 12L2 12", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M6 16L6.01 16", "M10 16L10.01 16"), // hard-drive
        "Bolt" to listOf("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"), // zap
        "Tune" to listOf("M21 4L14 4", "M10 4L3 4", "M21 12L12 12", "M8 12L3 12", "M21 20L16 20", "M12 20L3 20", "M14 2L14 6", "M8 10L8 14", "M16 18L16 22"), // sliders-horizontal
        "Wifi" to listOf("M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0"), // wifi
        "Shield" to listOf("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4"), // shield-check
        "Power" to listOf("M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04"), // power
        "ShieldCheck" to listOf("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4"), // shield-check
        "Users" to listOf("M18 21a8 8 0 0 0-16 0", "M5 8a5 5 0 1 0 10 0a5 5 0 1 0 -10 0", "M22 20c0-3.37-2-6.5-4-8a5 5 0 0 0-.45-8.3"), // users-round
        "Sliders" to listOf("M21 4L14 4", "M10 4L3 4", "M21 12L12 12", "M8 12L3 12", "M21 20L16 20", "M12 20L3 20", "M14 2L14 6", "M8 10L8 14", "M16 18L16 22"), // sliders-horizontal
    )

    private fun icon(name: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths.getValue(name)) addPath(
            addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    val Warning: ImageVector by lazy { icon("Warning") }
    val Info: ImageVector by lazy { icon("Info") }
    val CheckCircle: ImageVector by lazy { icon("CheckCircle") }
    val Refresh: ImageVector by lazy { icon("Refresh") }
    val Check: ImageVector by lazy { icon("Check") }
    val Share: ImageVector by lazy { icon("Share") }
    val ChevronUp: ImageVector by lazy { icon("ChevronUp") }
    val ChevronDown: ImageVector by lazy { icon("ChevronDown") }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight") }
    val ArrowLeft: ImageVector by lazy { icon("ArrowLeft") }
    val ArrowRight: ImageVector by lazy { icon("ArrowRight") }
    val Close: ImageVector by lazy { icon("Close") }
    val Plus: ImageVector by lazy { icon("Plus") }
    val Settings: ImageVector by lazy { icon("Settings") }
    val MoreVertical: ImageVector by lazy { icon("MoreVertical") }
    val MapPin: ImageVector by lazy { icon("MapPin") }
    val Paste: ImageVector by lazy { icon("Paste") }
    val Keyboard: ImageVector by lazy { icon("Keyboard") }
    val Qr: ImageVector by lazy { icon("Qr") }
    val File: ImageVector by lazy { icon("File") }
    val Cloud: ImageVector by lazy { icon("Cloud") }
    val Server: ImageVector by lazy { icon("Server") }
    val PersonAdd: ImageVector by lazy { icon("PersonAdd") }
    val Link: ImageVector by lazy { icon("Link") }
    val Globe: ImageVector by lazy { icon("Globe") }
    val Phone: ImageVector by lazy { icon("Phone") }
    val Key: ImageVector by lazy { icon("Key") }
    val Eye: ImageVector by lazy { icon("Eye") }
    val Cpu: ImageVector by lazy { icon("Cpu") }
    val Ram: ImageVector by lazy { icon("Ram") }
    val Disk: ImageVector by lazy { icon("Disk") }
    val Bolt: ImageVector by lazy { icon("Bolt") }
    val Tune: ImageVector by lazy { icon("Tune") }
    val Wifi: ImageVector by lazy { icon("Wifi") }
    val Shield: ImageVector by lazy { icon("Shield") }
    val Power: ImageVector by lazy { icon("Power") }
    val ShieldCheck: ImageVector by lazy { icon("ShieldCheck") }
    val Users: ImageVector by lazy { icon("Users") }
    val Sliders: ImageVector by lazy { icon("Sliders") }
}
