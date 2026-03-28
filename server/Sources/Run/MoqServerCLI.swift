import MoqCLI

@main
struct MoqServerCLIEntry {
    static func main() async throws {
        await MoqServerCLI.main()
    }
}
