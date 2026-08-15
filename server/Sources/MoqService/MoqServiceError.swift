import MoqFormat

/// Errors `MoqService` raises that aren't already covered by `ProjectStoreError` / `ProjectLoadError`.
public enum MoqServiceError: Error, CustomStringConvertible, Equatable, Sendable {
    case noProjectOpen
    case unsavedChanges
    case unknownSession(String)
    case endpointNotFound(String)
    case networkImportDisabled
    case importReadFailed(String)
    case importParseFailed(String)
    case importFetchFailed(String)

    public var description: String {
        switch self {
        case .noProjectOpen:
            return "No project is open in this session. Create or open one first."
        case .unsavedChanges:
            return "The current project has unsaved changes. Save first, or pass force to discard them."
        case .unknownSession(let handle):
            return "No session with handle \"\(handle)\". It may have been closed already."
        case .endpointNotFound(let id):
            return "No endpoint with id: \(id)"
        case .networkImportDisabled:
            return
                "URL import is disabled. Set MOQ_ALLOW_NETWORK_IMPORT=1 on the service process to enable it, or provide a local file path instead."
        case .importReadFailed(let path):
            return "Could not read file: \(path)"
        case .importParseFailed(let detail):
            return "Failed to parse import source: \(detail)"
        case .importFetchFailed(let detail):
            return "Failed to fetch import source: \(detail)"
        }
    }

    /// A stable machine-readable code, shared by every transport adapter (MCP, JSON-RPC) so a
    /// calling agent or Studio's error UI can branch on `code` rather than parse `description`.
    public var code: String {
        switch self {
        case .noProjectOpen: return "E_NO_PROJECT_OPEN"
        case .unsavedChanges: return "E_UNSAVED_CHANGES"
        case .unknownSession: return "E_UNKNOWN_SESSION"
        case .endpointNotFound: return "E_ENDPOINT_NOT_FOUND"
        case .networkImportDisabled: return "E_NETWORK_IMPORT_DISABLED"
        case .importReadFailed: return "E_IMPORT_READ_FAILED"
        case .importParseFailed: return "E_IMPORT_PARSE_FAILED"
        case .importFetchFailed: return "E_IMPORT_FETCH_FAILED"
        }
    }
}

/// Maps any error `MoqService` can throw to a stable `(code, message)` pair. Transport adapters
/// use this instead of re-deriving their own mapping, so MCP and JSON-RPC never disagree about
/// what a given failure is called.
public func moqServiceErrorCode(_ error: Error) -> (code: String, message: String) {
    switch error {
    case let error as MoqServiceError:
        return (error.code, error.description)
    case let error as ProjectStoreError:
        return (projectStoreErrorCode(error), error.description)
    case let error as ProjectLoadError:
        return ("E_LOAD_FAILED", error.description)
    case let error as ProjectValidationInputError:
        return ("E_RESERVED_PATH", error.description)
    default:
        return ("E_INTERNAL", "\(error)")
    }
}

private func projectStoreErrorCode(_ error: ProjectStoreError) -> String {
    switch error {
    case .projectAlreadyExists: return "E_PROJECT_ALREADY_EXISTS"
    case .endpointNotFound: return "E_ENDPOINT_NOT_FOUND"
    case .endpointAlreadyExists: return "E_ENDPOINT_ALREADY_EXISTS"
    case .endpointIDMismatch: return "E_ENDPOINT_ID_MISMATCH"
    case .variantNotFound: return "E_VARIANT_NOT_FOUND"
    case .invalidEndpointID: return "E_INVALID_ENDPOINT_ID"
    case .invalidFixturePath: return "E_INVALID_FIXTURE_PATH"
    case .fixtureNotFound: return "E_FIXTURE_NOT_FOUND"
    case .projectChangedOnDisk: return "E_PROJECT_CHANGED"
    case .projectRecoveryRequired: return "E_PROJECT_RECOVERY_REQUIRED"
    }
}
