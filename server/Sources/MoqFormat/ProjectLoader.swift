import Foundation
import MoqCore
import Yams

/// Loads a .moqproj directory from disk into domain models.
public struct ProjectLoader: ProjectLoading {
    public init() {}

    public func load(from path: String) throws -> MoqProject {
        let fm = FileManager.default
        let projectPath = (path as NSString).standardizingPath

        // Verify the directory exists
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: projectPath, isDirectory: &isDir), isDir.boolValue else {
            throw ProjectLoadError.notADirectory(projectPath)
        }

        // Load project.yml
        let manifestPath = (projectPath as NSString).appendingPathComponent("project.yml")
        guard fm.fileExists(atPath: manifestPath) else {
            throw ProjectLoadError.missingManifest(manifestPath)
        }
        let manifestYAML = try String(contentsOfFile: manifestPath, encoding: .utf8)
        let manifest: ProjectManifest
        do {
            manifest = try YAMLDecoder().decode(ProjectManifest.self, from: manifestYAML)
        } catch {
            throw ProjectLoadError.invalidManifest(manifestPath, error)
        }

        // Load endpoint files from endpoints/
        let endpointsDir = (projectPath as NSString).appendingPathComponent("endpoints")
        guard fm.fileExists(atPath: endpointsDir, isDirectory: &isDir), isDir.boolValue else {
            throw ProjectLoadError.missingEndpointsDirectory(endpointsDir)
        }

        let endpointFiles = try fm.contentsOfDirectory(atPath: endpointsDir)
            .filter { $0.hasSuffix(".yml") || $0.hasSuffix(".yaml") }
            .sorted()

        guard !endpointFiles.isEmpty else {
            throw ProjectLoadError.noEndpointFiles(endpointsDir)
        }

        var endpoints: [EndpointDocument] = []
        for file in endpointFiles {
            let filePath = (endpointsDir as NSString).appendingPathComponent(file)
            let yaml = try String(contentsOfFile: filePath, encoding: .utf8)
            do {
                let endpoint = try YAMLDecoder().decode(EndpointDocument.self, from: yaml)
                endpoints.append(endpoint)
            } catch {
                throw ProjectLoadError.invalidEndpointFile(filePath, error)
            }
        }

        return MoqProject(manifest: manifest, endpoints: endpoints, projectPath: projectPath)
    }
}

/// Errors that can occur when loading a .moqproj directory.
public enum ProjectLoadError: Error, CustomStringConvertible {
    case notADirectory(String)
    case missingManifest(String)
    case invalidManifest(String, Error)
    case missingEndpointsDirectory(String)
    case noEndpointFiles(String)
    case invalidEndpointFile(String, Error)

    public var description: String {
        switch self {
        case .notADirectory(let path):
            return "Not a directory: \(path)"
        case .missingManifest(let path):
            return "Missing project.yml at: \(path)"
        case .invalidManifest(let path, let error):
            return "Invalid project.yml at \(path): \(error)"
        case .missingEndpointsDirectory(let path):
            return "Missing endpoints/ directory at: \(path)"
        case .noEndpointFiles(let path):
            return "No endpoint files found in: \(path)"
        case .invalidEndpointFile(let path, let error):
            return "Invalid endpoint file \(path): \(error)"
        }
    }
}
