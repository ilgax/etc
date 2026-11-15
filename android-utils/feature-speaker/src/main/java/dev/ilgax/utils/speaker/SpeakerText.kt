package dev.ilgax.utils.speaker

import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.shizuku.ShizukuStatus

/** Pure text selection for the speaker module, kept free of Context so it can be unit tested. */
internal object SpeakerText {
    fun cardStatus(enabled: Boolean, shizuku: ShizukuStatus): UiText.Res = UiText.Res(
        when {
            !enabled -> R.string.speaker_card_off
            shizuku == ShizukuStatus.Ready -> R.string.speaker_card_on
            else -> R.string.speaker_card_on_volume_only
        },
    )

    fun headline(enabled: Boolean) =
        UiText.Res(if (enabled) R.string.speaker_headline_on else R.string.speaker_headline_off)

    fun supporting(enabled: Boolean, shizuku: ShizukuStatus) = UiText.Res(
        when {
            !enabled -> R.string.speaker_supporting_off
            shizuku == ShizukuStatus.Ready -> R.string.speaker_supporting_on
            else -> R.string.speaker_supporting_on_volume_only
        },
    )

    fun shizukuLabel(shizuku: ShizukuStatus) = UiText.Res(
        when (shizuku) {
            ShizukuStatus.Ready -> R.string.speaker_shizuku_ready
            ShizukuStatus.NotInstalled -> R.string.speaker_shizuku_not_installed
            ShizukuStatus.NotRunning -> R.string.speaker_shizuku_not_running
            ShizukuStatus.PermissionNeeded -> R.string.speaker_shizuku_permission
        },
    )
}
