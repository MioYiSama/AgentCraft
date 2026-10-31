package io.github.mioyi.agentcraft.core.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HistoryRepairTest {
    private fun twoCalls() = listOf(
        MessagePart.Tool.Call(id = "a", tool = "get_status", args = "{}"),
        MessagePart.Tool.Call(id = "b", tool = "walk_to", args = "{\"x\":1,\"y\":2,\"z\":3}"),
    )

    @Test
    fun `appends an error result for each unanswered call of the last assistant message`() {
        val p = prompt("t") {
            user("go")
            assistant(twoCalls())
            user { toolResult(id = "a", tool = "get_status", output = "ok") }
        }

        val repaired = closeDanglingToolCalls(p)

        assertEquals(p.messages, repaired.messages.dropLast(1))
        val added = repaired.messages.last()
        assertTrue(added is Message.User)
        val result = added.parts.single() as MessagePart.Tool.Result
        assertEquals("b", result.id)
        assertEquals("walk_to", result.tool)
        assertTrue(result.isError)
        assertTrue(result.output.startsWith("Cancelled:"))
    }

    @Test
    fun `fully answered prompt is returned unchanged`() {
        val p = prompt("t") {
            user("go")
            assistant(twoCalls())
            user {
                toolResult(id = "a", tool = "get_status", output = "ok")
                toolResult(id = "b", tool = "walk_to", output = "Arrived")
            }
            assistant("done")
        }

        assertSame(p, closeDanglingToolCalls(p))
    }
}
