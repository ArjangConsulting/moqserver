import Foundation
import Testing

@testable import MoqCore

@Suite("VariantNaming derives status-based fallbacks that agree with hand-authored casing")
struct VariantNamingTests {

    // ── defaultVariantBaseName ──────────────────────────────────────

    @Test("Generated base names are lowercase, matching hand-authored convention")
    func generatedBaseNamesAreLowercase() {
        // ProjectStore matches variant names case-insensitively, so a capitalized generated name
        // would collide with, but not spell the same as, a hand-authored "success"/"error" — the
        // exact mismatch that made HAR imports a trap. See ProjectStore.upsertVariant.
        #expect(defaultVariantBaseName(status: 200) == "success")
        #expect(defaultVariantBaseName(status: 299) == "success")
        #expect(defaultVariantBaseName(status: 404) == "error")
        #expect(defaultVariantBaseName(status: 599) == "error")
        #expect(defaultVariantBaseName(status: 100) == "variant")
    }

    // ── suggestedVariantName ────────────────────────────────────────

    @Test("suggestedVariantName falls back to a lowercase status-derived name with no preferred name")
    func suggestedNameFallsBackToLowercase() {
        #expect(suggestedVariantName(status: 200) == "success")
        #expect(suggestedVariantName(status: 500) == "error")
    }

    @Test("suggestedVariantName replaces a self-generated-looking preferred name, still lowercase")
    func suggestedNameReplacesGeneratedLookingInput() {
        // "Success" (any casing) looks like something this function itself would generate, so a
        // re-import doesn't accumulate "Success", "Success 2", ... — it's replaced with the
        // fresh, lowercase, status-derived name.
        #expect(suggestedVariantName(status: 200, preferredName: "Success") == "success")
        #expect(suggestedVariantName(status: 500, preferredName: "ERROR") == "error")
    }

    @Test("suggestedVariantName preserves a genuinely custom preferred name as-is")
    func suggestedNamePreservesCustomInput() {
        #expect(suggestedVariantName(status: 200, preferredName: "Happy Path") == "Happy Path")
    }
}
