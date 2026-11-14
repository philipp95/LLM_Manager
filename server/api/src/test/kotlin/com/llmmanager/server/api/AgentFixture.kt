package com.llmmanager.server.api

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

// Shared by tests that drive real git worktrees and the real run wrapper with fake agents.

class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** Runs each session's command to completion right away; no session stays alive. */
class ImmediateSessions : SessionHost {
    val started = mutableListOf<String>()
    override fun list(): Set<String> = emptySet()
    override fun start(name: String, directory: File, command: List<String>): Boolean {
        started += name
        val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor(60, TimeUnit.SECONDS)
    }
}

/**
 * Fake `claude`, `gemini` and `codex` executables in [root]/bin that emit the agents' event formats
 * and edit files. Prompt keywords: NOCHANGE edits nothing, LIMIT hits Claude's plan limit, a prompt starting with BREAK fails.
 */
class FakeAgents(private val root: File) {
    private fun script(name: String, body: String): String =
        File(root, "bin/$name").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env bash\n$body\n"); setExecutable(true) }.path

    /** Fake Claude: stream-json events; edits a file unless the prompt says NOCHANGE; "LIMIT" in the prompt hits the plan limit. */
    fun claude() = script(
        "claude",
        """
        prompt=${'$'}(cat)
        sid=""; prev=""
        for a in "${'$'}@"; do
          if [ "${'$'}prev" = "--session-id" ] || [ "${'$'}prev" = "--resume" ]; then sid="${'$'}a"; fi
          prev="${'$'}a"
        done
        echo "${'$'}prompt" >> "${'$'}PWD/../claude-prompts.log"
        echo '{"type":"system","subtype":"init","session_id":"'"${'$'}sid"'"}'
        if [[ "${'$'}prompt" == *LIMIT* ]] && [[ "${'$'}*" != *--resume* ]]; then
          echo '{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":1794232800,"rateLimitType":"five_hour"}}'
          echo '{"type":"result","is_error":true,"result":"Claude usage limit reached","session_id":"'"${'$'}sid"'"}'
          exit 1
        fi
        if [[ "${'$'}prompt" == BREAK* ]]; then
          echo '{"type":"result","is_error":true,"result":"Broken on purpose","session_id":"'"${'$'}sid"'"}'
          exit 1
        fi
        if [[ "${'$'}prompt" != *NOCHANGE* ]]; then echo "change ${'$'}RANDOM" >> claude-work.txt; fi
        echo '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"claude-work.txt"}}]}}'
        echo '{"type":"result","is_error":false,"result":"Implemented it.","session_id":"'"${'$'}sid"'"}'
        """.trimIndent(),
    )

    /** Fake Gemini: stream-json events; edits a file when not in plan (review) mode. */
    fun gemini() = script(
        "gemini",
        """
        prompt=${'$'}(cat)
        sid=""; prev=""; mode=""
        for a in "${'$'}@"; do
          if [ "${'$'}prev" = "--session-id" ] || [ "${'$'}prev" = "--resume" ]; then sid="${'$'}a"; fi
          if [ "${'$'}prev" = "--approval-mode" ]; then mode="${'$'}a"; fi
          prev="${'$'}a"
        done
        echo '{"type":"init","session_id":"'"${'$'}sid"'","model":"gemini-test"}'
        if [ "${'$'}mode" != "plan" ]; then echo "gemini ${'$'}RANDOM" >> gemini-work.txt; fi
        echo '{"type":"message","role":"assistant","content":"Gemini done.","delta":true}'
        echo '{"type":"result","status":"success","stats":{"input_tokens":10,"output_tokens":5,"cached":0,"models":{}}}'
        """.trimIndent(),
    )

    /** Fake Codex: `review` prints findings; `exec` emits JSON events. */
    fun codex() = script(
        "codex",
        """
        cat > /dev/null
        if [ "${'$'}1" = "review" ]; then echo "1. claude-work.txt: missing trailing newline check"; exit 0; fi
        echo '{"type":"thread.started","thread_id":"codex-thread-1"}'
        echo "codex ${'$'}RANDOM" >> codex-work.txt
        echo '{"type":"item.completed","item":{"type":"agent_message","text":"Done."}}'
        echo '{"type":"turn.completed"}'
        """.trimIndent(),
    )
}

/** Runs git in [dir] and returns its output; fails the test when git does. */
fun gitOutput(dir: File, vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $text" }
    return text.trim()
}
