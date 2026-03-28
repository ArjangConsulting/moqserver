package com.moqserver.studio

import java.awt.FileDialog
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileFilter
import java.awt.Window as AwtWindow

internal const val MOQ_PROJECT_EXTENSION = "moqproj"
private const val MAC_FILE_DIALOG_PACKAGES_PROPERTY = "apple.awt.use-file-dialog-packages"

/**
 * Shows a native file-picker dialog and returns the chosen file, or `null` if cancelled.
 *
 * On macOS the AWT [FileDialog] is used for a native look-and-feel.
 * On other platforms (when [allowDirectories] is `true`) a [JFileChooser] is used instead,
 * because the AWT dialog cannot select directories on non-Mac OSes.
 */
internal fun chooseFile(
    parent: AwtWindow?,
    title: String,
    extensions: Set<String>,
    initialDirectory: String?,
    treatPackagesAsFiles: Boolean = false,
    allowDirectories: Boolean = false,
): File? {
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    // On non-Mac, FileDialog cannot select directories — use JFileChooser instead
    if (allowDirectories && !isMac) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isAcceptAllFileFilterUsed = extensions.isEmpty()
            isFileHidingEnabled = false
            currentDirectory = initialDirectory?.let(::File)?.takeIf(File::isDirectory)
            if (extensions.isNotEmpty()) {
                fileFilter = object : FileFilter() {
                    override fun accept(file: File) =
                        file.isDirectory || extensions.any { ext -> file.name.endsWith(".$ext", ignoreCase = true) }

                    override fun getDescription() = extensions.joinToString(", ") { "*.$it" }
                }
            }
        }
        return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
    return withMacFileDialogPackages(treatPackagesAsFiles) {
        val dialog = createFileDialog(parent, title, FileDialog.LOAD).apply {
            isMultipleMode = false
            directory = initialDirectory?.takeIf { File(it).isDirectory }
            filenameFilter = FilenameFilter { _, name ->
                extensions.isEmpty() || extensions.any { ext ->
                    name.endsWith(".$ext", ignoreCase = true)
                }
            }
        }
        dialog.isVisible = true

        val directory = dialog.directory
        val file = dialog.file
        if (directory == null || file == null) {
            null
        } else {
            File(directory, file)
        }
    }
}

/**
 * Shows a native directory-picker and returns the chosen path, or `null` if cancelled.
 */
internal fun chooseDirectory(parent: AwtWindow?, title: String, initialDirectory: String?): String? {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        val previous = System.getProperty("apple.awt.fileDialogForDirectories")
        return try {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = createFileDialog(parent, title, FileDialog.LOAD).apply {
                directory = initialDirectory?.takeIf { File(it).isDirectory }
            }
            dialog.isVisible = true
            resolveSelectedDirectory(dialog.directory, dialog.file)
        } finally {
            if (previous == null) {
                System.clearProperty("apple.awt.fileDialogForDirectories")
            } else {
                System.setProperty("apple.awt.fileDialogForDirectories", previous)
            }
        }
    }

    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        currentDirectory = initialDirectory?.let(::File)?.takeIf(File::isDirectory)
    }
    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
        return chooser.selectedFile.canonicalPath
    }
    return null
}

/**
 * Presents a file-open dialog filtered to `.moqproj` packages / directories.
 */
internal fun chooseOpenProjectPath(parent: AwtWindow?, title: String, initialDirectory: String?): String? {
    return chooseFile(
        parent,
        title,
        extensions = setOf(MOQ_PROJECT_EXTENSION),
        initialDirectory = projectPickerInitialDirectory(initialDirectory),
        treatPackagesAsFiles = true,
        allowDirectories = true,
    )?.canonicalPath
}

/**
 * Presents a Save dialog for choosing a `.moqproj` save location.
 */
internal fun chooseProjectDirectory(
    parent: AwtWindow?,
    title: String,
    initialDirectory: String?,
    projectName: String,
): String? {
    val defaultFileName = buildProjectPackageName(projectName)
    val dialogDirectory = projectPickerInitialDirectory(initialDirectory)

    if (System.getProperty("os.name").lowercase().contains("mac")) {
        return withMacFileDialogPackages(true) {
            val dialog = createFileDialog(parent, title, FileDialog.SAVE).apply {
                directory = dialogDirectory
                file = defaultFileName
            }
            dialog.isVisible = true
            resolveSelectedProjectPath(dialog.directory, dialog.file, projectName)
        }
    }

    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false
        currentDirectory = dialogDirectory?.let(::File)?.takeIf(File::isDirectory)
        selectedFile = currentDirectory?.let { File(it, defaultFileName) } ?: File(defaultFileName)
        fileFilter = object : FileFilter() {
            override fun accept(file: File) =
                file.isDirectory || file.name.endsWith(".$MOQ_PROJECT_EXTENSION", ignoreCase = true)

            override fun getDescription() = "*.$MOQ_PROJECT_EXTENSION"
        }
    }

    return if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
        resolveSelectedProjectPath(
            directory = chooser.currentDirectory?.canonicalPath,
            file = chooser.selectedFile?.path,
            projectName = projectName,
        )
    } else {
        null
    }
}

// ---------------------------------------------------------------------------
// Pure helpers (tested via MainTest)
// ---------------------------------------------------------------------------

internal fun <T> withMacFileDialogPackages(enabled: Boolean, block: () -> T): T {
    if (!System.getProperty("os.name").lowercase().contains("mac")) {
        return block()
    }

    val previous = System.getProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY)
    return try {
        System.setProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY, enabled.toString())
        block()
    } finally {
        if (previous == null) {
            System.clearProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY)
        } else {
            System.setProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY, previous)
        }
    }
}

internal fun resolveSelectedDirectory(directory: String?, file: String?): String? {
    val selectedDirectory = directory
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf(File::isDirectory)
    val selectedFile = file?.takeIf { it.isNotBlank() }
    if (selectedDirectory == null && selectedFile == null) {
        return null
    }

    val resolved = if (selectedDirectory != null && selectedFile != null) {
        resolveChildPath(selectedDirectory, selectedFile)
    } else {
        selectedDirectory ?: File(selectedFile!!)
    }

    return resolved.takeIf(File::isDirectory)?.canonicalPath
}

internal fun resolveSelectedProjectPath(
    directory: String?,
    file: String?,
    projectName: String,
): String? {
    val selectedDirectory = directory
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf(File::isDirectory)
    val selectedFile = file?.trim()?.takeIf { it.isNotBlank() }?.let(::File)

    val resolved = when {
        selectedFile?.isAbsolute == true -> selectedFile
        selectedDirectory != null && selectedFile != null -> File(selectedDirectory, selectedFile.path)
        selectedDirectory != null -> File(selectedDirectory, buildProjectPackageName(projectName))
        else -> return null
    }

    return normalizeProjectPackagePath(resolved).canonicalPath
}

internal fun projectPickerInitialDirectory(initialDirectory: String?): String? {
    val directory = initialDirectory?.let(::File)?.takeIf(File::isDirectory)
    if (directory == null) {
        return null
    }
    if (directory.name.endsWith(".moqproj", ignoreCase = true)) {
        return directory.parentFile?.canonicalPath
    }
    return directory.canonicalPath
}

private fun resolveChildPath(
    directory: File,
    selectedFile: String,
): File {
    val candidate = File(selectedFile)
    return if (candidate.isAbsolute) candidate else File(directory, selectedFile)
}

internal fun normalizeProjectPackagePath(path: File): File {
    if (path.name.endsWith(".$MOQ_PROJECT_EXTENSION", ignoreCase = true)) {
        return path
    }
    return File(path.parentFile ?: File("."), "${path.name}.$MOQ_PROJECT_EXTENSION")
}

internal fun buildProjectPackageName(projectName: String): String {
    val cleanedName = projectName
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .ifBlank { "Untitled Project" }

    return if (cleanedName.endsWith(".moqproj", ignoreCase = true)) {
        cleanedName
    } else {
        "$cleanedName.moqproj"
    }
}

internal fun createFileDialog(parent: AwtWindow?, title: String, mode: Int): FileDialog {
    return when (parent) {
        is java.awt.Frame -> FileDialog(parent, title, mode)
        is java.awt.Dialog -> FileDialog(parent, title, mode)
        else -> FileDialog(null as java.awt.Frame?, title, mode)
    }
}
