/// Writes a MoqProject to a .moqproj directory deterministically.
public protocol ProjectWriting: Sendable {
    func write(_ project: MoqProject, to path: String) throws
}
