import Foundation
import Testing

@testable import MoqCore

@Suite("ReferenceNames generates valid programmatic identifiers")
struct ReferenceNamesTests {

    // ── isValidReferenceName ────────────────────────────────────────

    @Test("Valid reference names are accepted")
    func validNames() {
        #expect(isValidReferenceName("foo"))
        #expect(isValidReferenceName("fooBar"))
        #expect(isValidReferenceName("_private"))
        #expect(isValidReferenceName("foo123"))
        #expect(isValidReferenceName("FOO_BAR"))
        #expect(isValidReferenceName("a"))
        #expect(isValidReferenceName("_"))
    }

    @Test("Invalid reference names are rejected")
    func invalidNames() {
        #expect(!isValidReferenceName(""))
        #expect(!isValidReferenceName("123foo"))
        #expect(!isValidReferenceName("foo bar"))
        #expect(!isValidReferenceName("foo-bar"))
        #expect(!isValidReferenceName("foo.bar"))
    }

    // ── defaultReferenceNameForEndpointId ───────────────────────────

    @Test("Endpoint ID with hyphens becomes camelCase")
    func endpointIdCamelCase() {
        #expect(defaultReferenceNameForEndpointId("get-pets") == "getPets")
    }

    @Test("Single word endpoint ID stays lowercase")
    func endpointIdSingleWord() {
        #expect(defaultReferenceNameForEndpointId("users") == "users")
    }

    @Test("Endpoint ID with leading digits gets prefix")
    func endpointIdLeadingDigits() {
        #expect(defaultReferenceNameForEndpointId("123abc") == "endpoint123abc")
    }

    @Test("Endpoint ID that is all special chars becomes fallback")
    func endpointIdAllSpecial() {
        #expect(defaultReferenceNameForEndpointId("---") == "endpoint")
    }

    @Test("Empty endpoint ID returns fallback")
    func endpointIdEmpty() {
        #expect(defaultReferenceNameForEndpointId("") == "endpoint")
    }

    @Test("Endpoint ID with whitespace is trimmed and tokenized")
    func endpointIdWhitespace() {
        #expect(defaultReferenceNameForEndpointId("  get users  ") == "getUsers")
    }

    @Test("CamelCase endpoint ID is split and rejoined")
    func endpointIdCamelCaseSplit() {
        #expect(defaultReferenceNameForEndpointId("getUserProfile") == "getUserProfile")
    }

    // ── defaultReferenceNameForVariantName ──────────────────────────

    @Test("Variant name becomes camelCase")
    func variantNameCamelCase() {
        #expect(defaultReferenceNameForVariantName("Not Found") == "notFound")
    }

    @Test("Variant name with hyphens becomes camelCase")
    func variantNameHyphens() {
        #expect(defaultReferenceNameForVariantName("server-error") == "serverError")
    }

    @Test("Variant name with leading digit gets prefix")
    func variantNameLeadingDigit() {
        #expect(defaultReferenceNameForVariantName("404-error") == "variant404Error")
    }

    @Test("Empty variant name returns fallback")
    func variantNameEmpty() {
        #expect(defaultReferenceNameForVariantName("") == "variant")
    }

    // ── Generated names are valid ───────────────────────────────────

    @Test("Generated endpoint reference names are always valid")
    func generatedEndpointNamesValid() {
        let inputs = ["get-pets", "123abc", "---", "", "  spaces  ", "camelCase", "ALLCAPS"]
        for input in inputs {
            let name = defaultReferenceNameForEndpointId(input)
            #expect(isValidReferenceName(name), "Generated name '\(name)' from '\(input)' should be valid")
        }
    }

    @Test("Generated variant reference names are always valid")
    func generatedVariantNamesValid() {
        let inputs = ["Success", "not-found", "404 Error", "", "  ", "with spaces"]
        for input in inputs {
            let name = defaultReferenceNameForVariantName(input)
            #expect(isValidReferenceName(name), "Generated name '\(name)' from '\(input)' should be valid")
        }
    }
}
