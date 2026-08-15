import Foundation
import MoqCore
import MoqFormat

/// Owns at most one open `ProjectStore` for the lifetime of one stdio MCP process. Mutating
/// tools go through here rather than touching `ProjectStore` directly so session-level
/// concerns (no project open, unsaved-changes guard) live in one place.
public actor ProjectSession {
    private var store: ProjectStore?
    private var dirty = false

    public init() {}

    public var isOpen: Bool { store != nil }
    public var isDirty: Bool { dirty }

    public func currentStore() throws -> ProjectStore {
        guard let store else { throw SessionError.noProjectOpen }
        return store
    }

    public func create(manifest: ProjectManifest, at path: String, force: Bool) throws {
        try guardUnsavedChanges(force: force)
        store = try ProjectStore.create(manifest: manifest, at: path)
        dirty = false
    }

    public func open(path: String, force: Bool) throws {
        try guardUnsavedChanges(force: force)
        store = try ProjectStore(path: path)
        dirty = false
    }

    /// Marks the session dirty after a mutation and, when `autosave` is true, attempts to save.
    /// A save failure (including validation-unrelated I/O errors) leaves the mutation applied in
    /// memory and dirty — the caller sees the failure and can retry `moq_save_project`
    /// explicitly rather than losing the edit.
    public func recordMutation(autosave: Bool) async throws {
        dirty = true
        guard autosave, let store else { return }
        try await store.save()
        dirty = false
    }

    public func save() async throws {
        guard let store else { throw SessionError.noProjectOpen }
        try await store.save()
        dirty = false
    }

    private func guardUnsavedChanges(force: Bool) throws {
        guard dirty, !force else { return }
        throw SessionError.unsavedChanges
    }
}

public enum SessionError: Error, CustomStringConvertible, Equatable {
    case noProjectOpen
    case unsavedChanges

    public var description: String {
        switch self {
        case .noProjectOpen: return "No project is open. Call moq_create_project or moq_open_project first."
        case .unsavedChanges:
            return "The current project has unsaved changes. Save first, or pass force: true to discard them."
        }
    }
}
