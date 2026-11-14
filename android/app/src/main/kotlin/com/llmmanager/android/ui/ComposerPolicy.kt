package com.llmmanager.android.ui

import com.llmmanager.android.data.Project

/** Automatic publishing is opt-in and needs a forge remote. */
internal fun publishRequested(project: Project, checked: Boolean): Boolean = project.forge != null && checked
