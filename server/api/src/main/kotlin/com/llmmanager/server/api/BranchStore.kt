package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Push and pull request state per agent branch, written through to `branches.json`. */
class BranchStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val file = File(dir, "branches.json")
    private var records: List<BranchRecord> =
        if (file.isFile) json.decodeFromString(ListSerializer(BranchRecord.serializer()), file.readText()) else emptyList()

    @Synchronized fun get(projectId: String, branch: String): BranchRecord? = records.firstOrNull { it.projectId == projectId && it.branch == branch }

    @Synchronized fun put(record: BranchRecord) {
        records = records.filterNot { it.projectId == record.projectId && it.branch == record.branch } + record
        dir.mkdirs()
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(json.encodeToString(ListSerializer(BranchRecord.serializer()), records))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
