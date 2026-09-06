package com.darrenai.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v9 agentic loop (ReAct style): the model plans one action per
 * iteration, the loop executes it against on-device tools, feeds the
 * observation back, and repeats until DONE or [maxSteps].
 *
 * Protocol (one line from the model per iteration):
 *   ACTION: tool_name | arg text
 *   DONE: final answer to the user
 *
 * Tools are injected as a name -> function map so the ordering logic
 * stays unit-testable without Android or network.
 */
class AgentLoop(
    private val router: JarvisRouter,
    private val tools: Map<String, suspend (String) -> String> = emptyMap(),
    private val maxSteps: Int = 6
) {

    data class Step(
        val action: String,
        val arg: String,
        val observation: String
    )

    companion object {
        const val PREFIX = "agent:"

        fun systemPrompt(toolNames: List<String>): String = buildString {
            append("You are JARVIS, an autonomous on-device agent. Achieve the user's goal step by step. ")
            append("Tools available: ${toolNames.joinToString(", ")}. ")
            append("Reply with EXACTLY one line per turn: either 'ACTION: <tool> | <arg>' or 'DONE: <final answer>'. ")
            append("Use ACTION to gather facts or act (device_state, device_act, memory_search, memory_save, web). ")
            append("When the goal is achieved, reply DONE with the result in one short spoken-style paragraph. ")
            append("Never invent tool output; if a tool returns nothing useful, try one different tool, then DONE with what you have.")
        }

        /** Parse one planner line. Returns null when the line matches neither form. */
        fun parseLine(line: String): Parsed =
            runCatching {
                val t = line.trim()
                if (t.startsWith("ACTION:", ignoreCase = true)) {
                    val rest = t.substringAfter(":").trim()
                    val name = rest.substringBefore("|").trim().lowercase()
                    val arg = rest.substringAfter("|", "").trim()
                    Parsed.Action(name, arg)
                } else if (t.startsWith("DONE:", ignoreCase = true)) {
                    Parsed.Done(t.substringAfter(":").trim())
                } else {
                    Parsed.Done(t)
                }
            }.getOrDefault(Parsed.Done(line.trim()))
    }

    sealed class Parsed {
        data class Action(val tool: String, val arg: String) : Parsed()
        data class Done(val answer: String) : Parsed()
    }

    suspend fun run(
        goal: String,
        systemExtra: String = "",
        onStep: suspend (Step) -> Unit = {},
        onStatus: suspend (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val history = mutableListOf("user" to goal)
        val seen = StringBuilder()
        if (systemExtra.isNotBlank()) seen.append(systemExtra).append("\n")
        val sys = systemPrompt(tools.keys.toList()) + "\n" + seen.toString()

        repeat(maxSteps) { i ->
            onStatus("step ${i + 1}/$maxSteps reasoning…")
            val reply = askOnce(history, sys).trim().lineSequence().firstOrNull { it.isNotBlank() } ?: ""
            when (val p = parseLine(reply)) {
                is Parsed.Done -> return@withContext p.answer.ifBlank { "Done, sir — no further detail." }
                is Parsed.Action -> {
                    val fn = tools[p.tool]
                    val obs = if (fn == null) {
                        "unknown tool '${p.tool}'. Available: ${tools.keys.joinToString(", ")}"
                    } else {
                        runCatching { fn(p.arg) }.getOrElse { "tool error: ${it.message}" }
                    }
                    val step = Step(p.tool, p.arg, obs.take(800))
                    onStep(step)
                    history.add("assistant" to "ACTION: ${p.tool} | ${p.arg}")
                    history.add("user" to "OBSERVATION: ${step.observation}")
                }
            }
        }
        return@withContext "I ran $maxSteps steps on '$goal', sir — partial progress only. Ask me to continue with 'agent: continue $goal'."
    }

    private suspend fun askOnce(
        history: List<Pair<String, String>>,
        sys: String
    ): String {
        val buf = StringBuilder()
        var err: String? = null
        router.chatStream(
            messages = history,
            maxTokens = 256,
            systemExtra = sys,
            onBackend = {},
            onDelta = { buf.append(it) },
            onDone = { buf.clear().append(it) },
            onError = { err = it }
        )
        if (buf.isNotBlank()) return buf.toString()
        throw IllegalStateException(err ?: "planner produced no output")
    }
}
