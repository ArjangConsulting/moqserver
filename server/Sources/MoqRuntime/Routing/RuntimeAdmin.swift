import Foundation
import Logging
import Vapor

private let logger = Logger(label: "moqserver.runtime.RuntimeAdmin")

extension RuntimeScenario: Content {}
extension RequestTrace: Content {}
extension RuntimeSessionInfo: Content {}
extension RequestQueryResult: Content {}

extension AdminHandler {
    func scoped(req: Request) async throws -> AdminHandler {
        try requireAdminAuth(req: req)
        guard let id = req.headers.first(name: "X-Mock-Session") else { return self }
        guard let root = store as? InMemoryMockStore, let session = await root.runtimeSession(id) else {
            throw Abort(.notFound, reason: "Unknown mock session. Create one with POST /_admin/sessions.")
        }
        return AdminHandler(
            store: session, config: config, captureRequestHeaders: captureRequestHeaders,
            redactHeaders: redactHeaders)
    }

    /// POST /_admin/requests/query — evaluates a ``RequestQuery`` over the caller's history.
    func queryRequests(req: Request) async throws -> RequestQueryResult {
        try requireAdminAuth(req: req)
        let decoded = try req.content.decode(RequestQuery.self)
        let routeKeys = Set(await store.allEndpoints().map { "\($0.key.method.rawValue) \($0.key.path)" })
        let query: RequestQuery
        do {
            try decoded.validate(headerCaptureEnabled: captureRequestHeaders, redactHeaders: redactHeaders)
            query = try decoded.resolvingFilters(routeKeys: routeKeys)
        } catch let error as RequestQueryError {
            throw Abort(error == .headerCaptureDisabled ? .conflict : .badRequest, reason: error.description)
        }
        let rows: [RequestTrace]
        if query.scope == .sessionless {
            guard let id = req.headers.first(name: "X-Mock-Session") else {
                throw Abort(.badRequest, reason: RequestQueryError.sessionlessNeedsSession.description)
            }
            guard let root = store as? InMemoryMockStore, let createdAt = await root.runtimeSessionCreatedAt(id) else {
                throw Abort(.notFound, reason: "Unknown mock session. Create one with POST /_admin/sessions.")
            }
            rows = await root.recentRequests().reversed().filter { $0.timestamp >= createdAt }
        } else {
            rows = try await runtime(req: req).recentRequests().reversed()
        }
        let result = query.evaluate(rows, callerSession: req.headers.first(name: "X-Mock-Session"))
        logger.debug("Request query matched \(result.count) row(s), ok=\(result.ok)")
        return result
    }

    func runtime(req: Request) async throws -> InMemoryMockStore {
        let handler = try await scoped(req: req)
        guard let runtime = handler.store as? InMemoryMockStore else { throw Abort(.notImplemented) }
        return runtime
    }
}

extension AdminRouter {
    func registerRuntimeRoutes(on admin: RoutesBuilder) {
        admin.get("requests") { req async throws -> [RequestTrace] in
            try await handler.runtime(req: req).recentRequests()
        }
        admin.post("requests", "query") { req async throws -> RequestQueryResult in
            try await handler.queryRequests(req: req)
        }
        admin.post("requests", "mark") { req async throws -> [String: Double] in
            try handler.requireAdminAuth(req: req)
            return ["timestamp": Date().timeIntervalSince1970]
        }
        admin.delete("requests") { req async throws -> HTTPStatus in
            try await handler.runtime(req: req).clearRequestHistory()
            return .noContent
        }
        admin.get("scenarios") { req async throws -> [RuntimeScenario] in
            try await handler.runtime(req: req).allScenarios()
        }
        admin.put("scenarios") { req async throws -> HTTPStatus in
            let runtime = try await handler.runtime(req: req)
            let scenario = try req.content.decode(RuntimeScenario.self)
            do { try await runtime.defineScenario(scenario) } catch { throw scenarioAbort(error) }
            return .noContent
        }
        admin.delete("scenarios") { req async throws -> HTTPStatus in
            let runtime = try await handler.runtime(req: req)
            let input = try req.content.decode(ActivateScenario.self)
            await runtime.removeScenario(input.name)
            return .noContent
        }
        admin.put("scenario") { req async throws -> HTTPStatus in
            let runtime = try await handler.runtime(req: req)
            let input = try req.content.decode(ActivateScenario.self)
            do { try await runtime.activateScenario(input.name) } catch { throw scenarioAbort(error) }
            return .noContent
        }
        admin.delete("state") { req async throws -> HTTPStatus in
            try await handler.runtime(req: req).resetRuntimeState()
            return .noContent
        }
        admin.post("sessions") { req async throws -> [String: String] in
            try handler.requireAdminAuth(req: req)
            guard let root = handler.store as? InMemoryMockStore else { throw Abort(.notImplemented) }
            do { return ["id": try await root.createRuntimeSession()] } catch { throw scenarioAbort(error) }
        }
        admin.post("reload") { req async throws -> ReloadSummary in
            try handler.requireAdminAuth(req: req)
            guard let reloader = handler.reloader else {
                throw Abort(.notImplemented, reason: ProjectReloadError.notConfigured.description)
            }
            do {
                return try await reloader.reload()
            } catch let error as ProjectReloadError {
                throw Abort(.unprocessableEntity, reason: error.description)
            }
        }
        admin.get("sessions") { req async throws -> [RuntimeSessionInfo] in
            try handler.requireAdminAuth(req: req)
            guard let root = handler.store as? InMemoryMockStore else { throw Abort(.notImplemented) }
            return await root.listRuntimeSessions()
        }
        admin.delete("sessions", ":id") { req async throws -> HTTPStatus in
            try handler.requireAdminAuth(req: req)
            guard let root = handler.store as? InMemoryMockStore, let id = req.parameters.get("id") else {
                throw Abort(.badRequest)
            }
            await root.removeRuntimeSession(id)
            return .noContent
        }
    }
}

private struct ActivateScenario: Content { let name: String }

private func scenarioAbort(_ error: Error) -> Abort {
    switch error {
    case RuntimeScenarioError.invalidOverride(let key):
        return Abort(.badRequest, reason: "Unknown REST endpoint or variant: \(key)")
    case RuntimeScenarioError.invalidName:
        return Abort(.badRequest, reason: "Scenario name must contain 1–100 characters.")
    case RuntimeScenarioError.notFound: return Abort(.notFound, reason: "Scenario not found.")
    case RuntimeScenarioError.capacityExceeded:
        return Abort(
            .conflict,
            reason:
                "Scenario or session capacity reached; list sessions with GET /_admin/sessions and delete unused ones.")
    default: return Abort(.internalServerError, reason: "Could not update scenario.")
    }
}
