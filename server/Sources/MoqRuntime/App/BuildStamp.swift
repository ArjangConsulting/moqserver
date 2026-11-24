// Release builds overwrite this file with scripts/stamp-build-info.sh before compiling.
// Source builds keep both values nil and report themselves as unreleased.
enum BuildStamp {
    static let version: String? = nil
    static let commit: String? = nil
}
