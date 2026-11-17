package com.massisolutions.tridrone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

internal class ProjectScreens(
    private val activity: Activity,
    savedState: Bundle?,
    private val onProjectChanged: (SurveyProject?) -> Unit
) {
    private val store = ProjectStore.get(activity)
    private var pendingId = savedState?.getString("survey_export_id")
    private var pendingKind = savedState?.getString("survey_export_kind")

    fun restore() = run({ store.activeProject() }) { onProjectChanged(it) }

    fun save(state: Bundle) {
        state.putString("survey_export_id", pendingId)
        state.putString("survey_export_kind", pendingKind)
    }

    fun show() = run({ store.projects() to store.migrationWarnings }) { (projects, warnings) ->
        val labels = projects.map { it.name + if (it.archived) " (archived)" else "" }
        AlertDialog.Builder(activity).setTitle("Projects")
            .setItems(labels.toTypedArray()) { _, index -> showProject(projects[index].id) }
            .setPositiveButton("Create new project") { _, _ -> editProject(null) }
            .setNegativeButton("Close", null)
            .apply {
                if (warnings.isNotEmpty()) setNeutralButton("Import notices") { _, _ ->
                    showText("Previous recordings", warnings.joinToString("\n\n"))
                }
            }.show()
    }

    private fun showProject(id: String) = run({
        val project = store.projects().first { it.id == id }
        Triple(project, store.activeProject()?.id, store.sessions(id).size)
    }) { (project, activeId, count) ->
        val options = linkedMapOf<String, () -> Unit>()
        if (!project.archived && activeId != id) options["Open project"] = {
            changeSelection { store.selectProject(id) }
        }
        options["Collection history ($count)"] = { showHistory(project) }
        options["Rename project or edit description"] = { editProject(project) }
        options["Export project ZIP"] = { requestExport(id, "project") }
        val archive = if (project.archived) "Restore project" else "Archive project"
        options[archive] = {
            if (HydroLive.recording) message("Stop recording before archiving or restoring a project.")
            else AlertDialog.Builder(activity).setTitle(archive)
                .setMessage(if (project.archived) "Restore ${project.name} to active projects?"
                    else "Archive ${project.name}? Its collections will remain available to review and export.")
                .setPositiveButton(archive) { _, _ ->
                    changeSelection { store.archiveProject(id, !project.archived) }
                }
                .setNegativeButton("Cancel", null).show()
        }
        AlertDialog.Builder(activity)
            .setTitle(project.name + if (project.archived) " (archived)" else if (activeId == id) " (open)" else "")
            .setItems(options.keys.toTypedArray()) { _, index -> options.values.elementAt(index).invoke() }
            .setNeutralButton("Project details") { _, _ ->
                showText(project.name, listOfNotNull(project.description,
                    "Created ${date(project.createdUtc)}", "Modified ${date(project.modifiedUtc)}", project.id)
                    .joinToString("\n"))
            }
            .setNegativeButton("Projects") { _, _ -> show() }.show()
    }

    private fun changeSelection(change: () -> Unit) {
        if (HydroLive.recording) {
            message("Stop recording before changing projects.")
            return
        }
        run({ change(); store.activeProject() }) {
            onProjectChanged(it)
            show()
        }
    }

    private fun editProject(project: SurveyProject?) {
        if (project == null && HydroLive.recording) {
            message("Stop recording before creating a project.")
            return
        }
        val name = EditText(activity).apply {
            hint = "Project name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(project?.name.orEmpty())
            isSingleLine = true
        }
        val description = EditText(activity).apply {
            hint = "Description (optional)"
            setText(project?.description.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(name)
            addView(description)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(if (project == null) "Create new project" else "Edit project")
            .setView(form).setPositiveButton("Save", null).setNegativeButton("Cancel", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = name.text.toString().trim()
            if (title.isEmpty()) {
                name.error = "Enter a project name"
                return@setOnClickListener
            }
            val notes = description.text.toString().trim().ifEmpty { null }
            dialog.dismiss()
            run({
                if (project == null) store.createProject(title, notes).id else {
                    store.editProject(project.id, title, notes)
                    project.id
                }
            }) { id ->
                restore()
                showProject(id)
            }
        }
    }

    private fun showHistory(project: SurveyProject) = run({ store.sessions(project.id) }) { collections ->
        val dialog = AlertDialog.Builder(activity).setTitle("${project.name} collection history")
        if (collections.isEmpty()) dialog.setMessage("No collections recorded.")
        else dialog.setItems(collections.map { "${it.name}\n${summary(it)}" }.toTypedArray()) { _, index ->
            showCollection(project, collections[index].id)
        }
        dialog.setNegativeButton("Back") { _, _ -> showProject(project.id) }.show()
    }

    private fun showCollection(project: SurveyProject, id: String) = run({ checkNotNull(store.session(id)) }) { session ->
        val options = linkedMapOf<String, () -> Unit>()
        options["Collection statistics and settings"] = {
            showText(session.name, summary(session) + "\n\n" +
                "${session.validCount} valid paired soundings\n" +
                "${session.observationCount - session.validCount} other recorded events\n\n" +
                session.metadata.toString(2))
        }
        options["Review first 100 observations"] = {
            run({ store.previewRows(id) }) { showText("Observations • ${session.name}", it) }
        }
        options["Rename collection"] = { renameCollection(project, session) }
        if (session.status != CollectionStatus.RECORDING) {
            options["Export collection CSV"] = { requestExport(id, "csv") }
            options["Export collection audit ZIP"] = { requestExport(id, "audit") }
        }
        AlertDialog.Builder(activity).setTitle(session.name)
            .setItems(options.keys.toTypedArray()) { _, index -> options.values.elementAt(index).invoke() }
            .setNegativeButton("History") { _, _ -> showHistory(project) }.show()
    }

    private fun renameCollection(project: SurveyProject, session: CollectionSession) {
        val name = EditText(activity).apply { setText(session.name); isSingleLine = true }
        val dialog = AlertDialog.Builder(activity).setTitle("Rename collection").setView(name)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = name.text.toString().trim()
            if (value.isEmpty()) name.error = "Enter a collection name"
            else {
                dialog.dismiss()
                run({ store.renameSession(session.id, value) }) { showCollection(project, session.id) }
            }
        }
    }

    private fun summary(session: CollectionSession): String {
        val end = session.endedUtc
        val duration = end?.let { DateUtils.formatElapsedTime(((it - session.startedUtc) / 1000).coerceAtLeast(0)) }
        return "${date(session.startedUtc)} → ${end?.let(::date) ?: "ongoing"}" +
            "\n${duration ?: "In progress"} • ${session.observationCount} observations • ${session.status}"
    }

    private fun requestExport(id: String, kind: String) {
        if (HydroLive.recording) {
            message("Stop recording before exporting.")
            return
        }
        val csv = kind == "csv"
        val prefix = if (kind == "project") "project" else "collection"
        val extension = if (csv) "csv" else "zip"
        val stamp = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
        pendingId = id
        pendingKind = kind
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (csv) "text/csv" else "application/zip"
                putExtra(Intent.EXTRA_TITLE, "TriDrone-$prefix-$id-$stamp.$extension")
            }, EXPORT_REQUEST)
        } catch (error: Exception) {
            pendingId = null
            pendingKind = null
            message("Cannot open export destination. ${error.message.orEmpty()}")
        }
    }

    fun onExportResult(resultCode: Int, data: Intent?) {
        val id = pendingId
        val kind = pendingKind
        pendingId = null
        pendingKind = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null || id == null || kind == null) return
        run({
            val output = checkNotNull(activity.contentResolver.openOutputStream(uri)) { "Cannot open export destination" }
            output.use {
                when (kind) {
                    "project" -> store.exportProject(id, it)
                    "audit" -> store.exportAudit(id, it)
                    else -> store.exportSession(id, it)
                }
            }
        }, onError = {
            message("Export incomplete. Check the destination for a partial file. ${it.message.orEmpty()}")
        }) { message("Export saved.") }
    }

    private val padding get() = (20 * activity.resources.displayMetrics.density).toInt()
    private fun date(timestamp: Long) = DateFormat.getDateTimeInstance().format(Date(timestamp))

    private fun showText(title: String, content: String) {
        val view = ScrollView(activity).apply {
            addView(TextView(activity).apply {
                text = content
                setPadding(padding, padding, padding, padding)
                setTextIsSelectable(true)
            })
        }
        AlertDialog.Builder(activity).setTitle(title).setView(view).setPositiveButton("Close", null).show()
    }

    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun <T> run(
        work: () -> T,
        onError: (Throwable) -> Unit = { message("Survey storage error. ${it.message.orEmpty()}") },
        done: (T) -> Unit
    ) {
        worker.execute {
            val result = runCatching(work)
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing) {
                    result.onSuccess(done).onFailure(onError)
                }
            }
        }
    }

    companion object {
        const val EXPORT_REQUEST = 410
        private val worker = Executors.newSingleThreadExecutor()
    }
}
