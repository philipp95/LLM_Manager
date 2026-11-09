package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Jobs and runs, kept in memory and written through to JSON files in [dir] so
 * schedules and run history survive restarts. Writes are atomic (temp file + move).
 */
class StateStore(val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val jobsFile = File(dir, "jobs.json")
    private val runsFile = File(dir, "runs.json")
    private var jobs: List<Job> = load(jobsFile, Job.serializer())
    private var runs: List<Run> = load(runsFile, Run.serializer())


    @Synchronized fun jobs(): List<Job> = jobs

    @Synchronized fun runs(): List<Run> = runs

    @Synchronized fun job(id: String): Job? = jobs.firstOrNull { it.id == id }

    @Synchronized fun run(id: String): Run? = runs.firstOrNull { it.id == id }

    @Synchronized fun putJob(job: Job) {
        jobs = jobs.filterNot { it.id == job.id } + job
        save(jobsFile, jobs, Job.serializer())
    }

    @Synchronized fun removeJob(id: String) {
        jobs = jobs.filterNot { it.id == id }
        save(jobsFile, jobs, Job.serializer())
    }

    @Synchronized fun putRun(run: Run) {
        val others = runs.filterNot { it.id == run.id }
        // Keep history bounded: drop the oldest finished runs first.
        val finished = others.filter { it.status.finished }.sortedBy { it.createdAt }
        val excess = (others.size + 1 - MAX_RUNS).coerceAtLeast(0)
        val dropped = finished.take(excess).map { it.id }.toSet()
        runs = others.filterNot { it.id in dropped } + run
        save(runsFile, runs, Run.serializer())
    }

    /** Working files of one run: prompt, wrapper script, event stream, exit code. */
    fun runDir(id: String): File = File(dir, "runs/$id").also { it.mkdirs() }

    private fun <T> load(file: File, serializer: KSerializer<T>): List<T> =
        if (file.isFile) json.decodeFromString(ListSerializer(serializer), file.readText()) else emptyList()

    private fun <T> save(file: File, items: List<T>, serializer: KSerializer<T>) {
        dir.mkdirs()
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(json.encodeToString(ListSerializer(serializer), items))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val MAX_RUNS = 500
    }
}
