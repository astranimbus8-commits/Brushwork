package com.brushwork.paint.qa19

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A8 (R16; I27): the gallery's no-folder notice, the folder picker, the restore offer and the
 * restore list on the narrow 360 dp phone, at font scale 1 and at each of [Qa19Ui.FONT_SCALES]
 * ([Qa19Backup.chooseAndRestore]), "Later" putting the notice off ([Qa19Backup.later]), and the
 * other ways in: the empty gallery ([Qa19Backup.emptyGalleryRestore]), the editor Settings'
 * Backup section ([Qa19Backup.settings]) and the gallery's ⋮ menu ([Qa19Backup.menu]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.backup360sandbox"])
class Qa19BackupNarrowUiTest {

    @Test
    fun theBackupAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the notice, the offer and the list at font scale $scale") {
            Qa19Backup.chooseAndRestore(h, scale, 360f)
        }
        h.section("Later puts the notice off") { Qa19Backup.later(h, 360f) }
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the empty gallery's restore at font scale $scale") {
            Qa19Backup.emptyGalleryRestore(h, scale, 360f)
        }
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the Settings' Backup section at font scale $scale") {
            Qa19Backup.settings(h, scale, 360f)
        }
        h.section("the gallery's menu") { Qa19Backup.menu(h, 360f) }
    }
}
