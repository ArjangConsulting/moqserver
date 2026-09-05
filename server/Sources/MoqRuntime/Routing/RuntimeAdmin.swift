import Foundation
import Vapor

extension RuntimeScenario: Content {}
extension RequestTrace: Content {}

extension AdminHandler {
    func scoped(req: Request) async throws -> AdminHandler {
        try requireAdminAuth(req: req)
        guard let id = req.headers.first(name: "X-Mock-Session") else { return self }
        guard let root = store as? InMemoryMockStore, let session = await root.runtimeSession(id) else {
            throw Abort(.notFound, reason: "Unknown mock session. Create one with POST /_admin/sessions.")
        }
        return AdminHandler(store: session, config: config)
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
        return Abort(.conflict, reason: "Scenario or session capacity reached; remove unused sessions.")
    default: return Abort(.internalServerError, reason: "Could not update scenario.")
    }
}
