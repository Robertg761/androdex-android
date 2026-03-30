package io.androdex.android.ui.turn

import org.junit.Assert.assertEquals
import org.junit.Test

class ForkThreadSheetPresentationTest {
    @Test
    fun forkTargetActionLabel_usesHereCopy_forCurrentProjectTarget() {
        assertEquals("Fork here", forkTargetActionLabel(projectPath = null))
    }

    @Test
    fun forkTargetActionLabel_usesGenericCopy_forExplicitProjectTarget() {
        assertEquals("Fork", forkTargetActionLabel(projectPath = "/Users/robert/project"))
    }
}
