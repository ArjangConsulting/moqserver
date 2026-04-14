import Foundation
import Testing

@testable import MoqCore

@Suite("EndpointAlias generates human-readable names from method and path")
struct EndpointAliasTests {

    // ── Verb selection ──────────────────────────────────────────────

    @Test("GET collection path generates List verb")
    func getCollectionPath() {
        let alias = EndpointAlias.defaultAlias(method: "GET", path: "/pets")
        #expect(alias == "List Pets")
    }

    @Test("GET with path parameter generates Get verb")
    func getWithPathParameter() {
        let alias = EndpointAlias.defaultAlias(method: "GET", path: "/pets/{petId}")
        #expect(alias == "Get Pets By Pet Id")
    }

    @Test("POST generates Create verb")
    func postCreatesVerb() {
        let alias = EndpointAlias.defaultAlias(method: "POST", path: "/users")
        #expect(alias == "Create Users")
    }

    @Test("PUT generates Update verb")
    func putUpdatesVerb() {
        let alias = EndpointAlias.defaultAlias(method: "PUT", path: "/users/{userId}")
        #expect(alias == "Update Users By User Id")
    }

    @Test("PATCH generates Update verb")
    func patchUpdatesVerb() {
        let alias = EndpointAlias.defaultAlias(method: "PATCH", path: "/users/{userId}")
        #expect(alias == "Update Users By User Id")
    }

    @Test("DELETE generates Delete verb")
    func deleteVerb() {
        let alias = EndpointAlias.defaultAlias(method: "DELETE", path: "/users/{userId}")
        #expect(alias == "Delete Users By User Id")
    }

    @Test("HEAD generates Head verb")
    func headVerb() {
        let alias = EndpointAlias.defaultAlias(method: "HEAD", path: "/status")
        #expect(alias == "Head Status")
    }

    @Test("OPTIONS generates Options verb")
    func optionsVerb() {
        let alias = EndpointAlias.defaultAlias(method: "OPTIONS", path: "/config")
        #expect(alias == "Options Config")
    }

    @Test("Unknown method uses raw uppercase")
    func unknownMethod() {
        let alias = EndpointAlias.defaultAlias(method: "TRACE", path: "/debug")
        #expect(alias == "TRACE Debug")
    }

    // ── Path segment handling ───────────────────────────────────────

    @Test("api and version segments are ignored")
    func ignoredSegments() {
        let alias = EndpointAlias.defaultAlias(method: "GET", path: "/api/v2/users")
        #expect(alias == "List Users")
    }

    @Test("Root path generates Root label")
    func rootPath() {
        let alias = EndpointAlias.defaultAlias(method: "GET", path: "/")
        #expect(alias == "List Root")
    }

    @Test("Multiple path parameters produce combined By clause")
    func multiplePathParameters() {
        let alias = EndpointAlias.defaultAlias(method: "GET", path: "/teams/{teamId}/members/{memberId}")
        #expect(alias == "Get Teams Members By Team Id Member Id")
    }

    // ── Tokenization ────────────────────────────────────────────────

    @Test("camelCase path segments are tokenized")
    func camelCaseTokenization() {
        let alias = EndpointAlias.defaultAlias(method: "GET", path: "/userProfiles")
        #expect(alias == "List User Profiles")
    }

    @Test("snake_case path segments are tokenized")
    func snakeCaseTokenization() {
        let alias = EndpointAlias.defaultAlias(method: "GET", path: "/user_profiles")
        #expect(alias == "List User Profiles")
    }

    @Test("ALL-CAPS string is preserved when fully uppercase")
    func allCapsPreserved() {
        let humanized = EndpointAlias.humanize("HTTP")
        #expect(humanized == "HTTP")
    }

    // ── humanize ────────────────────────────────────────────────────

    @Test("humanize splits camelCase into title case words")
    func humanizeCamelCase() {
        #expect(EndpointAlias.humanize("getUserProfile") == "Get User Profile")
    }

    @Test("humanize handles underscores and hyphens")
    func humanizeMixed() {
        #expect(EndpointAlias.humanize("create_new-item") == "Create New Item")
    }

    // ── GraphQL ─────────────────────────────────────────────────────

    @Test("GraphQL with named operation uses humanized name")
    func graphqlNamedOperation() {
        let operation = EndpointOperation(type: .query, name: "getUser", document: nil)
        let alias = EndpointAlias.defaultAlias(method: "POST", path: "/graphql", operation: operation)
        #expect(alias == "Get User")
    }

    @Test("GraphQL path without name uses operation type label")
    func graphqlTypeLabel() {
        let operation = EndpointOperation(type: .mutation, name: nil, document: nil)
        let alias = EndpointAlias.defaultAlias(method: "POST", path: "/graphql", operation: operation)
        #expect(alias == "Mutation Operation")
    }

    // ── normalized(alias:) ──────────────────────────────────────────

    @Test("normalized returns nil for nil input")
    func normalizedNil() {
        #expect(EndpointAlias.normalized(alias: nil) == nil)
    }

    @Test("normalized returns nil for whitespace-only string")
    func normalizedBlank() {
        #expect(EndpointAlias.normalized(alias: "   ") == nil)
    }

    @Test("normalized returns trimmed non-empty string")
    func normalizedValid() {
        #expect(EndpointAlias.normalized(alias: "  Hello  ") == "Hello")
    }
}
