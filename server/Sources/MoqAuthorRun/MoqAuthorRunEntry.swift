import MoqAuthorCLI

@main
struct MoqAuthorRunEntry {
    static func main() async throws {
        await MoqAuthorCommand.runMain()
    }
}
