import Testing

@testable import MoqCore

@Suite("SecureCompare constant-time string equality")
struct SecureCompareTests {

    @Test("Equal strings compare equal")
    func equalStrings() {
        #expect(SecureCompare.equals("secret-token", "secret-token"))
        #expect(SecureCompare.equals("", ""))
        #expect(SecureCompare.equals("ünïcødé ✓", "ünïcødé ✓"))
    }

    @Test("Different strings compare unequal")
    func differentStrings() {
        #expect(!SecureCompare.equals("secret-token", "secret-tokeN"))
        #expect(!SecureCompare.equals("secret-token", "secret-token "))
        #expect(!SecureCompare.equals("a", "b"))
    }

    @Test("Different lengths compare unequal")
    func differentLengths() {
        #expect(!SecureCompare.equals("secret", "secret-token"))
        #expect(!SecureCompare.equals("secret-token", "secret"))
        #expect(!SecureCompare.equals("", "x"))
        #expect(!SecureCompare.equals("x", ""))
    }

    @Test("Common prefix does not compare equal")
    func commonPrefix() {
        #expect(!SecureCompare.equals("admin-token-1", "admin-token-2"))
    }
}
