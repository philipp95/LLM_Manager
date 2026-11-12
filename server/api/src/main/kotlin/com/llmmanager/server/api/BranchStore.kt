package com.llmmanager.server.api

import java.io.File

/** Push and pull request state per agent branch, written through to `branches.json`. */
class BranchStore(dir: File) {
    private val file = JsonListFile(File(dir, "branches.json"), BranchRecord.serializer())
    private var records: List<BranchRecord> = file.load()

    @Synchronized fun get(projectId: String, branch: String): BranchRecord? = records.firstOrNull { it.projectId == projectId && it.branch == branch }

    @Synchronized fun put(record: BranchRecord) {
        records = records.filterNot { it.projectId == record.projectId && it.branch == record.branch } + record
        file.save(records)
    }
}
