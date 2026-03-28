package com.moqserver.studio

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {

    @Test
    fun `resolveSelectedDirectory accepts mac directory-only selection`() {
        val selected = createTempDirectory("moqserver-main-test").toFile()

        try {
            assertEquals(
                selected.canonicalPath,
                resolveSelectedDirectory(selected.absolutePath, null),
            )
        } finally {
            selected.deleteRecursively()
        }
    }

    @Test
    fun `resolveSelectedDirectory combines parent directory and selected entry`() {
        val parent = createTempDirectory("moqserver-main-test-parent").toFile()
        val selected = File(parent, "Sample.moqproj").apply { mkdirs() }

        try {
            assertEquals(
                selected.canonicalPath,
                resolveSelectedDirectory(parent.absolutePath, selected.name),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `resolveSelectedDirectory returns null for non-directory selections`() {
        val parent = createTempDirectory("moqserver-main-test-file").toFile()
        val file = File(parent, "project.yml").apply { writeText("name: test") }

        try {
            assertNull(resolveSelectedDirectory(parent.absolutePath, file.name))
            assertNull(resolveSelectedDirectory(null, file.absolutePath))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `resolveSelectedProjectPath appends project extension for new saves`() {
        val parent = createTempDirectory("moqserver-main-test-save").toFile()

        try {
            assertEquals(
                File(parent, "Imported API.moqproj").canonicalPath,
                resolveSelectedProjectPath(parent.absolutePath, "Imported API", "Ignored"),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `resolveSelectedProjectPath defaults to sanitized project name`() {
        val parent = createTempDirectory("moqserver-main-test-default-save").toFile()

        try {
            assertEquals(
                File(parent, "My Project.moqproj").canonicalPath,
                resolveSelectedProjectPath(parent.absolutePath, null, "My/Project"),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `resolveSelectedProjectPath preserves explicit project package path`() {
        val parent = createTempDirectory("moqserver-main-test-existing-save").toFile()
        val selected = File(parent, "Existing.moqproj")

        try {
            assertEquals(
                selected.canonicalPath,
                resolveSelectedProjectPath(parent.absolutePath, selected.name, "Ignored"),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `withMacFileDialogPackages sets and restores property`() {
        val propertyName = "apple.awt.use-file-dialog-packages"
        val previousOsName = System.getProperty("os.name")
        val previousProperty = System.getProperty(propertyName)

        try {
            System.setProperty("os.name", "Mac OS X")
            System.setProperty(propertyName, "false")

            val seen = withMacFileDialogPackages(true) {
                System.getProperty(propertyName)
            }

            assertEquals("true", seen)
            assertEquals("false", System.getProperty(propertyName))
        } finally {
            if (previousOsName == null) {
                System.clearProperty("os.name")
            } else {
                System.setProperty("os.name", previousOsName)
            }

            if (previousProperty == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previousProperty)
            }
        }
    }

    @Test
    fun `withMacFileDialogPackages is a no-op on non-mac`() {
        val propertyName = "apple.awt.use-file-dialog-packages"
        val previousOsName = System.getProperty("os.name")
        val previousProperty = System.getProperty(propertyName)

        try {
            System.setProperty("os.name", "Linux")
            System.clearProperty(propertyName)

            val seen = withMacFileDialogPackages(true) {
                System.getProperty(propertyName)
            }

            assertNull(seen)
            assertTrue(System.getProperty(propertyName).isNullOrEmpty())
        } finally {
            if (previousOsName == null) {
                System.clearProperty("os.name")
            } else {
                System.setProperty("os.name", previousOsName)
            }

            if (previousProperty == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previousProperty)
            }
        }
    }
}
