package com.moqserver.studio

import androidx.compose.runtime.MutableState
import com.moqserver.studio.data.RecentProjectsRepository
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Image
import java.awt.Taskbar
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import java.awt.Window as AwtWindow

private val logger = loggerFor<StudioRootViewModel>()

internal const val STUDIO_APP_DISPLAY_NAME = "moqserver Studio"
internal const val STUDIO_APP_VERSION = "1.0.0"
private const val STUDIO_APP_ICON_RESOURCE = "/icons/icon.png"

internal enum class WindowCloseAction {
    CLOSE_PROJECT,
    EXIT_APPLICATION,
}

internal fun resolveWindowCloseAction(state: StudioState): WindowCloseAction {
    return if (state.project != null) WindowCloseAction.CLOSE_PROJECT else WindowCloseAction.EXIT_APPLICATION
}

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
    lastFileDirectory: MutableState<String?>,
    recentProjectsRepo: RecentProjectsRepository,
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
        runOnIo(ioDispatcher) { recentProjectsRepo.save(appViewModel.state.value.recentProjects) }
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
 * Persists [project] to [path] and updates view-model state and the recent-projects list.
 * Shared by Save, Save As, and the import confirmation flows.
 */
internal suspend fun persistProject(
    project: com.moqserver.studio.projectformat.MoqProject,
    path: String,
    repo: ProjectRepository,
    appViewModel: StudioRootViewModel,
    recentProjectsRepo: RecentProjectsRepository,
    ioDispatcher: CoroutineDispatcher,
) {
    runOnIo(ioDispatcher) { repo.save(project, path) }
    appViewModel.projectSaved(path)
    appViewModel.addRecentProject(path)
    runOnIo(ioDispatcher) { recentProjectsRepo.save(appViewModel.state.value.recentProjects) }
}

/**
 * If the current project has unsaved changes, prompts the user to confirm closing.
 * Returns `true` when the transition may proceed (user confirmed), `false` on cancel.
 */
internal fun confirmProjectTransition(
    owner: AwtWindow?,
    state: StudioState,
): Boolean {
    if (state.project == null || !state.isDirty) return true

    val result = JOptionPane.showConfirmDialog(
        owner,
        "Are you sure you want to close the project? You have unsaved changes that you'd lose.",
        "Close Project",
        JOptionPane.YES_NO_OPTION,
    )

    return when (result) {
        JOptionPane.YES_OPTION -> {
            logger.info("User chose to close project without saving")
            true
        }
        else -> {
            logger.debug("Project close cancelled by user")
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

internal fun installAboutHandler(showAbout: () -> Unit) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_ABOUT)) {
        return
    }

    desktop.setAboutHandler { showAbout() }
}

internal fun installPreferencesHandler(showPreferences: () -> Unit) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
        return
    }

    desktop.setPreferencesHandler { showPreferences() }
}

internal fun isMacOs(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

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
