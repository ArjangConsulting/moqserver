import Foundation
import Testing
@testable import MoqCore
@testable import MoqRuntime

struct InMemoryMockStoreTests {
    func makeEndpoint(method: HTTPMethodValue, path: String, statusCode: UInt = 200, body: String = "{}") -> Endpoint {
        Endpoint(
            key: EndpointKey(method: method, path: path),
            authRequirement: .none,
            variants: [
                ResponseVariant(
                    name: "default",
                    statusCode: HTTPStatusCode(code: statusCode),
                    body: Data(body.utf8)
                )
            ]
        )
    }

    @Test("Exact path lookup")
    func exactPathLookup() async {
        let store = InMemoryMockStore()
        let endpoint = makeEndpoint(method: .get, path: "/pets")
        await store.register(endpoint)

        let result = await store.lookup(method: .get, path: "/pets")
        #expect(result != nil)
        #expect(result?.key.path == "/pets")
    }

    @Test("Returns nil for unregistered path")
    func missingPath() async {
        let store = InMemoryMockStore()
        let result = await store.lookup(method: .get, path: "/unknown")
        #expect(result == nil)
    }

    @Test("Method must match")
    func methodMismatch() async {
        let store = InMemoryMockStore()
        let endpoint = makeEndpoint(method: .get, path: "/pets")
        await store.register(endpoint)

        let result = await store.lookup(method: .post, path: "/pets")
        #expect(result == nil)
    }

    @Test("Path parameter matching")
    func pathParameterMatching() async {
        let store = InMemoryMockStore()
        let endpoint = makeEndpoint(method: .get, path: "/pets/{petId}")
        await store.register(endpoint)

        let result = await store.lookup(method: .get, path: "/pets/123")
        #expect(result != nil)
        #expect(result?.key.path == "/pets/{petId}")
    }

    @Test("Path parameter does not match extra segments")
    func pathParameterNoExtraSegments() async {
        let store = InMemoryMockStore()
        let endpoint = makeEndpoint(method: .get, path: "/pets/{petId}")
        await store.register(endpoint)

        let result = await store.lookup(method: .get, path: "/pets/123/toys")
        #expect(result == nil)
    }

    @Test("Multiple endpoints coexist")
    func multipleEndpoints() async {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", body: "[\"list\"]"))
        await store.register(makeEndpoint(method: .post, path: "/pets", body: "{\"created\":true}"))
        await store.register(makeEndpoint(method: .get, path: "/pets/{petId}", body: "{\"id\":1}"))

        let list = await store.lookup(method: .get, path: "/pets")
        let create = await store.lookup(method: .post, path: "/pets")
        let getOne = await store.lookup(method: .get, path: "/pets/42")

        #expect(list != nil)
        #expect(create != nil)
        #expect(getOne != nil)

        let allEndpoints = await store.allEndpoints()
        #expect(allEndpoints.count == 3)
    }

    // MARK: - GraphQL Lookup

    func makeGraphQLEndpoint(
        operationName: String?,
        operationType: EndpointOperation.OperationType = .query,
        document: String? = nil,
        body: String = "{}"
    ) -> Endpoint {
        Endpoint(
            key: EndpointKey(method: .post, path: "/graphql"),
            authRequirement: .none,
            variants: [
                ResponseVariant(
                    name: "default",
                    statusCode: .ok,
                    body: Data(body.utf8)
                )
            ],
            operation: EndpointOperation(type: operationType, name: operationName, document: document)
        )
    }

    @Test("GraphQL lookup by operation name")
    func graphqlLookupByName() async {
        let store = InMemoryMockStore()
        await store.register(makeGraphQLEndpoint(operationName: "GetUser", body: #"{"data":{"user":{}}}"#))
        await store.register(makeGraphQLEndpoint(operationName: "ListPets", body: #"{"data":{"pets":[]}}"#))

        let user = await store.lookupGraphQL(method: .post, path: "/graphql", operationName: "GetUser", operationType: .query, normalizedDocument: nil)
        #expect(user != nil)
        #expect(user?.operation?.name == "GetUser")

        let pets = await store.lookupGraphQL(method: .post, path: "/graphql", operationName: "ListPets", operationType: .query, normalizedDocument: nil)
        #expect(pets != nil)
        #expect(pets?.operation?.name == "ListPets")
    }

    @Test("GraphQL lookup returns nil for unknown operation")
    func graphqlLookupUnknown() async {
        let store = InMemoryMockStore()
        await store.register(makeGraphQLEndpoint(operationName: "GetUser"))

        let result = await store.lookupGraphQL(method: .post, path: "/graphql", operationName: "DeleteUser", operationType: nil, normalizedDocument: nil)
        // Falls back to first registered
        #expect(result?.operation?.name == "GetUser")
    }

    @Test("GraphQL lookup by operation type when name is nil")
    func graphqlLookupByType() async {
        let store = InMemoryMockStore()
        await store.register(makeGraphQLEndpoint(operationName: nil, operationType: .mutation, body: #"{"data":{}}"#))
        await store.register(makeGraphQLEndpoint(operationName: "GetUser", operationType: .query, body: #"{"data":{"user":{}}}"#))

        let mutation = await store.lookupGraphQL(method: .post, path: "/graphql", operationName: nil, operationType: .mutation, normalizedDocument: nil)
        #expect(mutation?.operation?.type == .mutation)
    }

    @Test("GraphQL lookup by normalized document for anonymous operations")
    func graphqlLookupByDocument() async {
        let store = InMemoryMockStore()
        await store.register(makeGraphQLEndpoint(
            operationName: nil,
            operationType: .query,
            document: "query { currentUser { id name } }",
            body: #"{"data":{"currentUser":{"id":"1"}}}"#
        ))
        await store.register(makeGraphQLEndpoint(
            operationName: nil,
            operationType: .query,
            document: "query { currentAccount { id } }",
            body: #"{"data":{"currentAccount":{"id":"2"}}}"#
        ))

        let result = await store.lookupGraphQL(
            method: .post,
            path: "/graphql",
            operationName: nil,
            operationType: .query,
            normalizedDocument: "query { currentUser { id name } }"
        )

        #expect(result?.operation?.document == "query { currentUser { id name } }")
    }

    @Test("GraphQL endpoints included in allEndpoints")
    func graphqlInAllEndpoints() async {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets"))
        await store.register(makeGraphQLEndpoint(operationName: "GetUser"))
        await store.register(makeGraphQLEndpoint(operationName: "ListPets"))

        let all = await store.allEndpoints()
        #expect(all.count == 3)
    }

    @Test("GraphQL endpoints don't conflict with regular lookup")
    func graphqlDoesNotConflictWithRegular() async {
        let store = InMemoryMockStore()
        await store.register(makeGraphQLEndpoint(operationName: "GetUser"))

        // Regular lookup should not find GraphQL-only endpoints
        let regular = await store.lookup(method: .post, path: "/graphql")
        #expect(regular == nil)
    }

    @Test("Merging variants updates existing names and appends new ones")
    func mergeVariants() async throws {
        let store = InMemoryMockStore()
        await store.register(Endpoint(
            key: EndpointKey(method: .get, path: "/pets"),
            authRequirement: .none,
            variants: [
                ResponseVariant(name: "default", statusCode: .ok, body: Data("old".utf8)),
            ]
        ))

        await store.mergeVariants(from: Endpoint(
            key: EndpointKey(method: .get, path: "/pets"),
            authRequirement: .none,
            variants: [
                ResponseVariant(name: "default", statusCode: .ok, body: Data("new".utf8)),
                ResponseVariant(name: "error-404", statusCode: .notFound, body: Data("missing".utf8)),
            ]
        ))

        let endpoint = await store.lookup(method: .get, path: "/pets")
        #expect(endpoint?.variants.count == 2)
        let defaultBody = try #require(endpoint?.variants.first { $0.name == "default" }?.body)
        #expect(String(data: defaultBody, encoding: .utf8) == "new")
        #expect(endpoint?.variants.contains { $0.name == "error-404" } == true)
    }

    @Test("Variant override persistence survives store recreation")
    func variantOverridePersistence() async {
        let persistencePath = (NSTemporaryDirectory() as NSString).appendingPathComponent("variant-overrides-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(atPath: persistencePath) }

        let firstStore = InMemoryMockStore()
        await firstStore.configureVariantOverridePersistence(path: persistencePath)
        await firstStore.setVariantOverride(for: "GET /pets", variant: "error-500")

        let secondStore = InMemoryMockStore()
        await secondStore.configureVariantOverridePersistence(path: persistencePath)
        #expect(await secondStore.activeVariantOverride(for: "GET /pets") == "error-500")

        await secondStore.resetVariantOverride(for: "GET /pets")
        #expect(await secondStore.activeVariantOverride(for: "GET /pets") == nil)
    }

    @Test("Registering GraphQL endpoint with same operation name replaces prior entry")
    func graphQLRegistrationReplacesSameOperationName() async throws {
        let store = InMemoryMockStore()
        await store.register(makeGraphQLEndpoint(operationName: "GetUser", body: "first"))
        await store.register(makeGraphQLEndpoint(operationName: "GetUser", body: "second"))

        let endpoint = await store.lookupGraphQL(method: .post, path: "/graphql", operationName: "GetUser", operationType: .query, normalizedDocument: nil)
        let body = try #require(endpoint?.variants.first?.body)
        #expect(String(data: body, encoding: .utf8) == "second")
        #expect((await store.allEndpoints()).count == 1)
    }
}
