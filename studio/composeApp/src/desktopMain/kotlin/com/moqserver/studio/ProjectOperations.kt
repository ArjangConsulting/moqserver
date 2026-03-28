package com.moqserver.studio

import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Image
import java.awt.Taskbar
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import java.awt.Window as AwtWindow

private val logger = loggerFor<StudioRootViewModel>()

internal const val STUDIO_APP_DISPLAY_NAME = "Moq Studio"
internal const val STUDIO_APP_VERSION = "1.0.0"
private const val STUDIO_APP_ICON_RESOURCE = "/icons/icon.png"

// ---------------------------------------------------------------------------
// Project open / save / transition
// ---------------------------------------------------------------------------

/**
 * Resolves [rawPath] to a `.moqproj` project directory, loads it, and pushes the result
 * into [appViewModel].
 */
internal suspend fun openProject(
    rawPath: String,
    repo: ProjectRepository,
    appViewModel: StudioRootViewModel,
    lastFileDirectory: androidx.compose.runtime.MutableState<String?>,
    ioDispatcher: CoroutineDispatcher,
) {
    val path = resolveProjectPath(rawPath)
        ?: run {
            logger.warn("Path is not a .{} project: {}", MOQ_PROJECT_EXTENSION, rawPath)
            appViewModel.setError("Not a .$MOQ_PROJECT_EXTENSION project: $rawPath")
            return
        }

    logger.info("Opening project: {}", path)
    try {
        val project = runOnIo(ioDispatcher) { repo.load(path) }
        appViewModel.projectLoaded(project)
        appViewModel.addRecentProject(path)
        lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
        logger.info("Project '{}' opened successfully", project.manifest.name)
    } catch (e: Exception) {
        reportRecoverable(
            context = "Failed to load project",
            throwable = e,
            onUserMessage = appViewModel::setError,
        )
    }
}

/**
 * Checks whether the application can exit safely; prompts the user to save if dirty.
 * Returns `true` when the exit may proceed.
 */
internal fun confirmExit(
    owner: AwtWindow?,
    state: com.moqserver.studio.domain.StudioState,
    repo: ProjectRepository,
    appViewModel: StudioRootViewModel,
    lastFileDirectory: androidx.compose.runtime.MutableState<String?>,
    ioDispatcher: CoroutineDispatcher,
): Boolean {
    if (state.project == null || !state.isDirty) return true
    return confirmProjectTransition(owner, state, repo, appViewModel, lastFileDirectory, ioDispatcher)
}

/**
 * If the current project has unsaved changes, prompts the user to save/discard/cancel.
 * Returns `true` when the transition may proceed (saved or discarded), `false` on cancel.
 */
internal fun confirmProjectTransition(
    owner: AwtWindow?,
    state: com.moqserver.studio.domain.StudioState,
    repo: ProjectRepository,
    appViewModel: StudioRootViewModel,
    lastFileDirectory: androidx.compose.runtime.MutableState<String?>,
    ioDispatcher: CoroutineDispatcher,
): Boolean {
    if (state.project == null || !state.isDirty) return true

    val result = JOptionPane.showConfirmDialog(
        owner,
        "You have unsaved changes. Save before continuing?",
        "Unsaved Changes",
        JOptionPane.YES_NO_CANCEL_OPTION,
    )

    return when (result) {
        JOptionPane.YES_OPTION -> {
            val project = state.project ?: return true
            val path = if (project.projectPath.isBlank()) {
                chooseProjectDirectory(
                    parent = owner,
                    title = "Save Project",
                    initialDirectory = null,
                    projectName = project.manifest.name,
                ) ?: return false
            } else {
                project.projectPath
            }

            logger.info("Saving project before continuing: {}", path)
            try {
                runBlocking {
                    runOnIo(ioDispatcher) { repo.save(project, path) }
                }
                appViewModel.projectSaved(path)
                appViewModel.addRecentProject(path)
                lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
                true
            } catch (throwable: Throwable) {
                if (isFailFastEnabled()) {
                    throw propagateFailure("Failed to save before closing", throwable)
                }
                reportFatal("Failed to save before closing", throwable)
                false
            }
        }
        JOptionPane.NO_OPTION -> {
            logger.info("User chose to continue without saving")
            true
        }
        else -> {
            logger.debug("Project transition cancelled by user")
            false
        }
    }
}

// ---------------------------------------------------------------------------
// About dialog
// ---------------------------------------------------------------------------

internal fun showAboutDialog(owner: AwtWindow?) {
    JOptionPane.showMessageDialog(
        owner,
        "$STUDIO_APP_DISPLAY_NAME\nVersion $STUDIO_APP_VERSION",
        "About $STUDIO_APP_DISPLAY_NAME",
        JOptionPane.INFORMATION_MESSAGE,
    )
}

// ---------------------------------------------------------------------------
// OS integration (app icon, file-open handler)
// ---------------------------------------------------------------------------

internal fun installAppIcon(window: AwtWindow) {
    val icon = loadAppIcon() ?: return
    window.iconImages = listOf(icon)

    runCatching {
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.iconImage = icon
            }
        }
    }.onFailure { throwable ->
        logger.debug("Failed to install taskbar icon", throwable)
    }
}

private fun loadAppIcon(): Image? = runCatching {
    Thread.currentThread().contextClassLoader
        .getResourceAsStream(STUDIO_APP_ICON_RESOURCE.removePrefix("/"))
        ?.use(ImageIO::read)
}.onFailure { throwable ->
    logger.warn("Failed to load app icon from {}", STUDIO_APP_ICON_RESOURCE, throwable)
}.getOrNull()

internal fun installProjectOpenHandler(onOpenProject: (String) -> Unit) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
    if (desktop == null || !desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
        return
    }

    desktop.setOpenFileHandler { event ->
        val path = event.files.firstNotNullOfOrNull { file ->
            resolveProjectPath(file.canonicalPath)
        }
        if (path != null) {
            onOpenProject(path)
        }
    }
}

// ---------------------------------------------------------------------------
// Path resolution
// ---------------------------------------------------------------------------

internal fun resolveInitialProjectPath(args: Array<String>): String? {
    return args.firstNotNullOfOrNull(::resolveProjectPath)
}

internal fun resolveProjectPath(rawPath: String): String? {
    val candidate = runCatching { File(rawPath).canonicalFile }.getOrNull()
    if (candidate == null) {
        return null
    }
    val projectDirectory = generateSequence(candidate) { it.parentFile }
        .firstOrNull(::isProjectDirectory)
    return projectDirectory?.canonicalPath
}

private fun isProjectDirectory(file: File): Boolean {
    return file.isDirectory && (
        file.name.endsWith(".$MOQ_PROJECT_EXTENSION", ignoreCase = true) ||
            File(file, "project.yml").isFile
        )
}

private suspend fun <T> runOnIo(
    ioDispatcher: CoroutineDispatcher,
    block: suspend () -> T,
): T {
    return withContext(ioDispatcher) {
        block()
    }
}
