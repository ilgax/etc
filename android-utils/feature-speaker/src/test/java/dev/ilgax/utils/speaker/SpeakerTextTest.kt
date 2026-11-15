package dev.ilgax.utils.speaker

import dev.ilgax.utils.core.shizuku.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTextTest {
    @Test
    fun cardStatusFollowsSwitchAndShizuku() {
        assertEquals(R.string.speaker_card_off, SpeakerText.cardStatus(false, ShizukuStatus.Ready).id)
        assertEquals(R.string.speaker_card_off, SpeakerText.cardStatus(false, ShizukuStatus.NotRunning).id)
        assertEquals(R.string.speaker_card_on, SpeakerText.cardStatus(true, ShizukuStatus.Ready).id)
        for (s in listOf(ShizukuStatus.NotInstalled, ShizukuStatus.NotRunning, ShizukuStatus.PermissionNeeded)) {
            assertEquals(R.string.speaker_card_on_volume_only, SpeakerText.cardStatus(true, s).id)
        }
    }

    @Test
    fun supportingTextMentionsVolumeOnlyFallback() {
        assertEquals(R.string.speaker_supporting_on_volume_only, SpeakerText.supporting(true, ShizukuStatus.NotRunning).id)
        assertEquals(R.string.speaker_supporting_on, SpeakerText.supporting(true, ShizukuStatus.Ready).id)
        assertEquals(R.string.speaker_supporting_off, SpeakerText.supporting(false, ShizukuStatus.Ready).id)
    }

    @Test
    fun everyShizukuStatusHasALabel() {
        val ids = ShizukuStatus.entries.map { SpeakerText.shizukuLabel(it).id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
