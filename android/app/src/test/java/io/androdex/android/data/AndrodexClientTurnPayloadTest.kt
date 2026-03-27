package io.androdex.android.data

import io.androdex.android.ComposerReviewTarget
import io.androdex.android.model.AccessMode
import io.androdex.android.model.CollaborationModeKind
import io.androdex.android.model.ImageAttachment
import io.androdex.android.model.TurnSkillMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexClientTurnPayloadTest {
    @Test
    fun buildTurnStartParams_includesPlanCollaborationSettings() {
        val params = buildTurnStartPayloadSpec(
            threadId = "thread-1",
            userInput = "Make a plan",
            model = "gpt-5.4",
            reasoningEffort = "high",
            serviceTier = null,
            collaborationMode = CollaborationModeKind.PLAN,
        )

        assertEquals("thread-1", params["threadId"])
        assertEquals("gpt-5.4", params["model"])
        assertEquals("high", params["effort"])
        assertEquals("Make a plan", ((params["input"] as List<*>).single() as Map<*, *>)["text"])

        val collaborationMode = params["collaborationMode"] as Map<*, *>
        assertEquals("plan", collaborationMode["mode"])
        val settings = collaborationMode["settings"] as Map<*, *>
        assertEquals("gpt-5.4", settings["model"])
        assertEquals("high", settings["reasoning_effort"])
        assertTrue(settings["developer_instructions"] == null)
    }

    @Test
    fun buildTurnSteerParams_omitsCollaborationModeWhenNotRequested() {
        val params = buildTurnSteerPayloadSpec(
            threadId = "thread-2",
            expectedTurnId = "turn-9",
            userInput = "Keep going",
            model = "gpt-5.4",
            reasoningEffort = "medium",
            serviceTier = null,
            collaborationMode = null,
        )

        assertEquals("thread-2", params["threadId"])
        assertEquals("turn-9", params["expectedTurnId"])
        assertEquals("medium", params["effort"])
        assertFalse(params.containsKey("collaborationMode"))
    }

    @Test
    fun buildTurnInputPayload_includesStructuredSkillItemsWhenRequested() {
        val payload = buildTurnInputPayloadSpec(
            userInput = "Use \$frontend-design",
            skillMentions = listOf(
                TurnSkillMention(
                    id = "frontend-design",
                    name = "frontend-design",
                    path = "C:\\Users\\rober\\.codex\\skills\\frontend-design\\SKILL.md",
                )
            ),
        )

        assertEquals(2, payload.size)
        assertEquals("text", payload[0]["type"])
        assertEquals("Use \$frontend-design", payload[0]["text"])
        assertEquals("skill", payload[1]["type"])
        assertEquals("frontend-design", payload[1]["id"])
        assertEquals("frontend-design", payload[1]["name"])
        assertEquals(
            "C:\\Users\\rober\\.codex\\skills\\frontend-design\\SKILL.md",
            payload[1]["path"],
        )
    }

    @Test
    fun buildTurnInputPayload_placesImagesBeforeTextAndSupportsLegacyImageUrlRetry() {
        val payload = buildTurnInputPayloadSpec(
            userInput = "Describe this photo",
            attachments = listOf(
                ImageAttachment(
                    id = "image-1",
                    thumbnailBase64Jpeg = "thumb",
                    payloadDataUrl = "data:image/jpeg;base64,AAAA",
                )
            ),
            imageUrlKey = "image_url",
        )

        assertEquals(2, payload.size)
        assertEquals("image", payload[0]["type"])
        assertEquals("data:image/jpeg;base64,AAAA", payload[0]["image_url"])
        assertEquals("text", payload[1]["type"])
        assertEquals("Describe this photo", payload[1]["text"])
    }

    @Test
    fun shouldRetryTurnWithImageUrlField_matchesLegacyServerErrors() {
        assertTrue(shouldRetryTurnWithImageUrlField("Missing required field image_url in image item"))
        assertFalse(shouldRetryTurnWithImageUrlField("Invalid skill item"))
    }

    @Test(expected = IllegalStateException::class)
    fun buildCollaborationModePayload_requiresModelForPlanMode() {
        buildCollaborationModePayloadSpec(
            collaborationMode = CollaborationModeKind.PLAN,
            model = null,
            reasoningEffort = null,
        )
    }

    @Test
    fun buildCollaborationModePayload_returnsNullForDefaultChatTurns() {
        assertNull(
            buildCollaborationModePayloadSpec(
                collaborationMode = null,
                model = "gpt-5.4",
                reasoningEffort = "medium",
            )
        )
    }

    @Test
    fun buildTurnStartPayload_includesServiceTierWhenRequested() {
        val params = buildTurnStartPayloadSpec(
            threadId = "thread-1",
            userInput = "Ship it",
            model = "gpt-5.4",
            reasoningEffort = "high",
            serviceTier = "fast",
            collaborationMode = null,
        )

        assertEquals("fast", params["serviceTier"])
    }

    @Test
    fun buildTurnSteerPayload_includesServiceTierWhenRequested() {
        val params = buildTurnSteerPayloadSpec(
            threadId = "thread-1",
            expectedTurnId = "turn-1",
            userInput = "Keep going",
            model = "gpt-5.4",
            reasoningEffort = "high",
            serviceTier = "fast",
            collaborationMode = null,
        )

        assertEquals("fast", params["serviceTier"])
    }

    @Test
    fun buildReviewStartPayload_includesInlineDeliveryAndTargetSchema() {
        val params = buildReviewStartPayloadSpec(
            threadId = "thread-1",
            target = ComposerReviewTarget.BASE_BRANCH,
            baseBranch = "main",
        )

        assertEquals("thread-1", params["threadId"])
        assertEquals("inline", params["delivery"])
        val target = params["target"] as Map<*, *>
        assertEquals("baseBranch", target["type"])
        assertEquals("main", target["branch"])
    }

    @Test
    fun buildThreadForkPayload_includesRuntimeOverridesAndSandbox() {
        val params = buildThreadForkPayloadSpec(
            sourceThreadId = "thread-1",
            preferredProjectPath = "C:\\Projects\\Androdex",
            model = "gpt-5.4",
            serviceTier = "fast",
            includeSandbox = true,
            usesMinimalForkParams = false,
            accessMode = AccessMode.FULL_ACCESS,
        )

        assertEquals("thread-1", params["threadId"])
        assertEquals("C:\\Projects\\Androdex", params["cwd"])
        assertEquals("gpt-5.4", params["model"])
        assertEquals("fast", params["serviceTier"])
        assertEquals("danger-full-access", params["sandbox"])
    }

    @Test
    fun buildThreadForkPayload_minimalModeOnlySendsSourceThread() {
        val params = buildThreadForkPayloadSpec(
            sourceThreadId = "thread-1",
            preferredProjectPath = "C:\\Projects\\Androdex",
            model = "gpt-5.4",
            serviceTier = "fast",
            includeSandbox = true,
            usesMinimalForkParams = true,
            accessMode = AccessMode.ON_REQUEST,
        )

        assertEquals(mapOf("threadId" to "thread-1"), params)
    }

    @Test
    fun applyAccessModeParams_prefersSandboxPolicyWhenAvailable() {
        val params = applyAccessModeParams(
            baseParams = org.json.JSONObject().put("threadId", "thread-1"),
            accessMode = AccessMode.ON_REQUEST,
            sandboxMode = AccessModeSandboxMode.SANDBOX_POLICY,
        )

        val policy = params.getJSONObject("sandboxPolicy")
        assertEquals("workspaceWrite", policy.getString("type"))
        assertTrue(policy.getBoolean("networkAccess"))
        assertFalse(params.has("sandbox"))
    }

    @Test
    fun buildSandboxPolicyPayload_matchesFullAccessSpec() {
        val payload = buildSandboxPolicyPayloadSpec(AccessMode.FULL_ACCESS)

        assertEquals("dangerFullAccess", payload["type"])
    }
}
