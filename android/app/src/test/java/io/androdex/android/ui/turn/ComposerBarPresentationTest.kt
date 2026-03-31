package io.androdex.android.ui.turn

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerBarPresentationTest {
    @Test
    fun composerHasVisibleContextChips_ignoresModeOnlyState() {
        assertEquals(
            false,
            composerHasVisibleContextChips(
                hasMentionedFiles = false,
                hasMentionedSkills = false,
                hasActiveModes = true,
            ),
        )
    }

    @Test
    fun composerHasVisibleContextChips_showsRowForFileOrSkillMentions() {
        assertEquals(
            true,
            composerHasVisibleContextChips(
                hasMentionedFiles = true,
                hasMentionedSkills = false,
                hasActiveModes = false,
            ),
        )
        assertEquals(
            true,
            composerHasVisibleContextChips(
                hasMentionedFiles = false,
                hasMentionedSkills = true,
                hasActiveModes = false,
            ),
        )
    }

    @Test
    fun composerAccessoryButtonState_prefersCloseWhilePanelIsVisible() {
        assertEquals(
            ComposerAccessoryButtonState.CLOSE,
            composerAccessoryButtonState(
                hasActiveModes = true,
                isModePanelVisible = true,
            ),
        )
    }

    @Test
    fun composerAccessoryButtonState_usesTuneWhenModesAreArmed() {
        assertEquals(
            ComposerAccessoryButtonState.TUNE,
            composerAccessoryButtonState(
                hasActiveModes = true,
                isModePanelVisible = false,
            ),
        )
    }

    @Test
    fun composerSubmitPresentation_keepsPlainSendAsIconOnly() {
        assertEquals(
            ComposerSubmitPresentation.ICON,
            composerSubmitPresentation(
                submitButtonLabel = "Send",
                showStop = false,
            ),
        )
    }

    @Test
    fun composerSubmitPresentation_usesTextForQueuedAndArmedStates() {
        assertEquals(
            ComposerSubmitPresentation.TEXT,
            composerSubmitPresentation(
                submitButtonLabel = "Queue Plan",
                showStop = true,
            ),
        )
    }
}
