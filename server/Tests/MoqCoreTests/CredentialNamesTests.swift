import Testing

@testable import MoqCore

struct CredentialNamesTests {
    private let jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1c2VyLTEifQ."
    private let names = CredentialNames.builtIn

    @Test("Ordinary names that contain credential words are not credentials")
    func noSubstringGuessing() {
        for header in ["X-Author-Id", "X-Session-Ref", "X-Keyword", "X-Signature-Version", "Monkey"] {
            #expect(!names.isCredentialHeader(header), "\(header)")
        }
        for parameter in ["keyword", "sort_key", "monkey", "author", "session_count", "page_token", "code", "state"] {
            #expect(!names.isCredentialQueryParameter(parameter), "\(parameter)")
        }
    }

    @Test("Built-in names match ignoring case, '-' and '_'")
    func builtInsNormalize() {
        for header in ["authorization", "X-AUTHORIZATION", "x_api_key", "ApiKey", "X-CSRFToken", "x-firebase-appcheck"]
        {
            #expect(names.isCredentialHeader(header), "\(header)")
        }
        for parameter in ["access_token", "apiKey", "API-KEY", "client_secret", "X-Amz-Signature"] {
            #expect(names.isCredentialQueryParameter(parameter), "\(parameter)")
        }
    }

    @Test("Extra names are added and exempt names removed, but protected headers stay")
    func extraAndExempt() {
        let custom = CredentialNames(
            extraHeaders: ["X-Session-Ref"], extraQueryParameters: ["sig"],
            exceptHeaders: ["X-Id-Token", "Authorization"], exceptQueryParameters: ["token"])
        #expect(custom.isCredentialHeader("x-session-ref"))
        #expect(custom.isCredentialQueryParameter("SIG"))
        #expect(!custom.isCredentialHeader("X-Id-Token"))
        #expect(custom.isCredentialHeader("Authorization"))
        #expect(!custom.isExemptHeader("Authorization"))
        #expect(!custom.isCredentialQueryParameter("token"))
    }

    @Test("A JWT value is a credential under any name except an exempt one")
    func jwtValues() {
        #expect(names.isCredentialHeader("X-Correlation", value: "Bearer \(jwt)"))
        #expect(names.isCredentialQueryParameter("cursor", value: jwt))
        let exempt = CredentialNames(exceptHeaders: ["X-Id-Token"], exceptQueryParameters: ["cursor"])
        #expect(!exempt.isCredentialHeader("X-Id-Token", value: jwt))
        #expect(!exempt.isCredentialQueryParameter("cursor", value: jwt))
    }

    @Test("The JWT pattern needs two eyJ parts at a token boundary")
    func jwtPatternIsStrict() {
        #expect(CredentialNames.containsJWT(jwt))
        #expect(CredentialNames.containsJWT("token=\(jwt)"))
        #expect(!CredentialNames.containsJWT("https://monkeys.eyewear.example.test/"))
        #expect(!CredentialNames.containsJWT("key.eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1c2VyLTEifQ"))
        #expect(!CredentialNames.containsJWT("ey.ey.sig"))
    }

    @Test("Adding a name ends its exemption")
    func addingEndsExemption() {
        let names = CredentialNames(exceptHeaders: ["X-Api-Key"]).adding(headers: ["x-api-key"])
        #expect(names.isCredentialHeader("X-Api-Key"))
        #expect(!names.isExemptHeader("X-Api-Key"))
    }
}
