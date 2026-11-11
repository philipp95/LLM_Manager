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
    init {
        // Prompts, run logs and branch records: readable by this user only.
        SecureFiles.privateDir(dir, tighten = true)
    }

    private val jobsFile = JsonListFile(File(dir, "jobs.json"), Job.serializer())
    private val runsFile = JsonListFile(File(dir, "runs.json"), Run.serializer())
    private var jobs: List<Job> = jobsFile.load()
    private var runs: List<Run> = runsFile.load()

    @Synchronized fun jobs(): List<Job> = jobs

    @Synchronized fun runs(): List<Run> = runs

    @Synchronized fun job(id: String): Job? = jobs.firstOrNull { it.id == id }

    @Synchronized fun run(id: String): Run? = runs.firstOrNull { it.id == id }

    @Synchronized fun putJob(job: Job) {
        jobs = jobs.filterNot { it.id == job.id } + job
        jobsFile.save(jobs)
    }

    @Synchronized fun removeJob(id: String) {
        jobs = jobs.filterNot { it.id == id }
        jobsFile.save(jobs)
    }

    @Synchronized fun putRun(run: Run) {
        val others = runs.filterNot { it.id == run.id }
        // Keep history bounded: drop the oldest finished runs first.
        val finished = others.filter { it.status.finished }.sortedBy { it.createdAt }
        val excess = (others.size + 1 - MAX_RUNS).coerceAtLeast(0)
        val dropped = finished.take(excess).map { it.id }.toSet()
        runs = others.filterNot { it.id in dropped } + run
        runsFile.save(runs)
        dropped.forEach { deleteRunDir(it) }
    }

    /** Working files of one run: prompt, wrapper script, event stream, exit code. */
    fun runDir(id: String): File {
        require(RUN_ID.matches(id)) { "Invalid run id" }
        return File(dir, "runs/$id").also { it.mkdirs() }
    }

    /** Removes run directories that no longer belong to a run in the history (e.g. after a crash). */
    @Synchronized fun pruneRunDirs() {
        val known = runs.map { it.id }.toSet()
        File(dir, "runs").listFiles().orEmpty().filter { it.isDirectory && RUN_ID.matches(it.name) && it.name !in known }.forEach { deleteRunDir(it.name) }
    }

    private fun deleteRunDir(id: String) {
        // Only ever directly below runs/, named like a run id: never follow a crafted path.
        if (RUN_ID.matches(id)) File(dir, "runs/$id").deleteRecursively()
    }

    companion object {
        /** Runs kept in the history; older finished runs (and their files) are dropped. */
        const val MAX_RUNS = 500
        private val RUN_ID = Regex("[0-9a-f]{12}")
    }
}

/** A list persisted as JSON, written atomically (temp file + move) so a crash never leaves half a file. */
class JsonListFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): List<T> = if (file.isFile) json.decodeFromString(ListSerializer(serializer), file.readText()) else emptyList()

    fun save(items: List<T>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(ListSerializer(serializer), items))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * Applies [change] to the stored run in one step, under the store's own lock, so a concurrent
 * writer cannot lose the update; null when the run is gone.
 */
fun StateStore.updateRun(id: String, change: (Run) -> Run): Run? = synchronized(this) { run(id)?.let(change)?.also(::putRun) }
