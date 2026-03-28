/// Loads a .moqproj directory into domain models.
public protocol ProjectLoading: Sendable {
    func load(from path: String) throws -> MoqProject
}
