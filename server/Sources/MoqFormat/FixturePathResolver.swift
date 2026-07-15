import Foundation

enum FixturePathResolver {
    static func resolve(bodyFile: String, projectPath: String) -> URL? {
        guard bodyFile.hasPrefix("fixtures/"), !bodyFile.contains("\0") else { return nil }

        let projectURL = URL(fileURLWithPath: projectPath, isDirectory: true)
            .resolvingSymlinksInPath().standardizedFileURL
        let fixturesURL = projectURL.appendingPathComponent("fixtures", isDirectory: true)
            .resolvingSymlinksInPath().standardizedFileURL
        let fixtureURL = projectURL.appendingPathComponent(bodyFile).resolvingSymlinksInPath().standardizedFileURL
        let projectPrefix = projectURL.path.hasSuffix("/") ? projectURL.path : projectURL.path + "/"
        let fixturesPrefix = fixturesURL.path.hasSuffix("/") ? fixturesURL.path : fixturesURL.path + "/"

        guard fixturesURL.path.hasPrefix(projectPrefix), fixtureURL.path.hasPrefix(fixturesPrefix) else { return nil }
        return fixtureURL
    }
}
