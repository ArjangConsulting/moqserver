/// Constant-time string comparison for secrets.
///
/// Comparing tokens or credentials with `==` short-circuits on the first mismatched
/// byte, which can leak how much of a guess was correct through response timing.
public enum SecureCompare {
    /// Returns `true` when both strings contain the same UTF-8 bytes.
    /// The comparison time depends only on the candidate's length, not on its content.
    public static func equals(_ lhs: String, _ rhs: String) -> Bool {
        let lhsBytes = Array(lhs.utf8)
        let rhsBytes = Array(rhs.utf8)
        var difference = UInt8(lhsBytes.count == rhsBytes.count ? 0 : 1)
        for index in 0..<rhsBytes.count {
            let lhsByte = index < lhsBytes.count ? lhsBytes[index] : 0
            difference |= lhsByte ^ rhsBytes[index]
        }
        return difference == 0
    }
}
