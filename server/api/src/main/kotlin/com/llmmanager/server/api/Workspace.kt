package com.llmmanager.server.api

/** What runs need from the machine: projects, terminal sessions, agent CLIs, git and the agent sandbox. */
class Workspace(
    val scanner: ProjectScanner,
    val sessions: SessionHost,
    val commands: AgentCommands,
    val git: Git = Git(),
    val sandbox: AgentSandbox = AgentSandbox.disabled(),
)
