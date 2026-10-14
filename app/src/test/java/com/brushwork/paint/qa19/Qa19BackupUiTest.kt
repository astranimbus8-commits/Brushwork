package com.brushwork.paint.qa19

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A8 (R16; I27): the gallery's no-folder notice, the folder picker, the restore offer and the
 * restore list on the 392 dp phone, at font scale 1 and at each of [Qa19Ui.FONT_SCALES]
 * ([Qa19Backup.chooseAndRestore]), and "Later" putting the notice off ([Qa19Backup.later]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.backup392sandbox"])
class Qa19BackupUiTest {

    @Test
    fun theBackupAt392AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the notice, the offer and the list at font scale $scale") {
            Qa19Backup.chooseAndRestore(h, scale, 392f)
        }
        h.section("Later puts the notice off") { Qa19Backup.later(h, 392f) }
    }
}
