import Foundation
import Testing
@testable import MoqCore
@testable import MoqParsing

@Suite("OpenAPISpecValidator")
struct OpenAPISpecValidatorTests {
    let validator = OpenAPISpecValidator()

    func loadFixture(_ name: String) throws -> Data {
        let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!
        return try Data(contentsOf: url)
    }

    @Test("Valid spec produces no errors")
    func validSpec() throws {
        let data = try loadFixture("petstore.yaml")
        let diagnostics = validator.validate(data: data)
        let errors = diagnostics.filter { $0.severity == .error }
        #expect(errors.isEmpty)
    }

    @Test("Detects missing operationId")
    func missingOperationId() throws {
        let data = try loadFixture("petstore-validation-issues.yaml")
        let diagnostics = validator.validate(data: data)
        let noOpId = diagnostics.filter { $0.message.contains("has no operationId") }
        #expect(!noOpId.isEmpty)
    }

    @Test("Detects missing success response")
    func missingSuccessResponse() throws {
        let data = try loadFixture("petstore-validation-issues.yaml")
        let diagnostics = validator.validate(data: data)
        let noSuccess = diagnostics.filter { $0.message.contains("no success (2xx)") }
        #expect(!noSuccess.isEmpty)
    }

    @Test("Detects deprecated operations")
    func deprecatedOperation() throws {
        let data = try loadFixture("petstore-validation-issues.yaml")
        let diagnostics = validator.validate(data: data)
        let deprecated = diagnostics.filter { $0.message.contains("deprecated") }
        #expect(!deprecated.isEmpty)
    }

    @Test("Warns about empty response body for GET")
    func emptyResponseBody() throws {
        let data = try loadFixture("petstore-validation-issues.yaml")
        let diagnostics = validator.validate(data: data)
        let emptyBody = diagnostics.filter { $0.message.contains("no content") && $0.message.contains("empty body") }
        #expect(!emptyBody.isEmpty)
    }

    @Test("Warns about schema without example")
    func schemaWithoutExample() throws {
        let data = try loadFixture("petstore-validation-issues.yaml")
        let diagnostics = validator.validate(data: data)
        let noExample = diagnostics.filter { $0.message.contains("schema but no example") }
        #expect(!noExample.isEmpty)
    }

    @Test("Warns about content with neither schema nor example")
    func noSchemaNoExample() throws {
        let data = try loadFixture("petstore-validation-issues.yaml")
        let diagnostics = validator.validate(data: data)
        let neither = diagnostics.filter { $0.message.contains("neither example nor schema") }
        #expect(!neither.isEmpty)
    }

    @Test("Invalid data produces parse error")
    func invalidData() {
        let data = Data("not a valid spec".utf8)
        let diagnostics = validator.validate(data: data)
        let errors = diagnostics.filter { $0.severity == .error }
        #expect(!errors.isEmpty)
        #expect(errors.first?.message.contains("Failed to parse") == true)
    }

    @Test("Valid JSON spec produces no errors")
    func validJSONSpec() {
        let json = """
        {
            "openapi": "3.0.0",
            "info": {"title": "Test", "version": "1.0"},
            "paths": {
                "/test": {
                    "get": {
                        "operationId": "getTest",
                        "responses": {
                            "200": {
                                "description": "OK",
                                "content": {
                                    "application/json": {
                                        "schema": {"type": "object"},
                                        "example": {"key": "value"}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: json)
        let errors = diagnostics.filter { $0.severity == .error }
        #expect(errors.isEmpty)
    }

    @Test("Detects empty paths")
    func emptyPaths() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "Empty"
          version: "1.0"
        paths: {}
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        let pathErrors = diagnostics.filter { $0.message.contains("no paths") }
        #expect(!pathErrors.isEmpty)
        #expect(pathErrors.first?.severity == .error)
    }

    @Test("Detects empty title")
    func emptyTitle() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "  "
          version: "1.0"
        paths:
          /test:
            get:
              operationId: getTest
              responses:
                "200":
                  description: OK
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        let titleWarnings = diagnostics.filter { $0.message.contains("title is empty") }
        #expect(!titleWarnings.isEmpty)
    }

    @Test("Detects empty version")
    func emptyVersion() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "Test"
          version: "  "
        paths:
          /test:
            get:
              operationId: getTest
              responses:
                "200":
                  description: OK
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        let versionWarnings = diagnostics.filter { $0.message.contains("version is empty") }
        #expect(!versionWarnings.isEmpty)
    }

    @Test("Detects no responses defined")
    func noResponsesDefined() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "Test"
          version: "1.0"
        paths:
          /test:
            get:
              operationId: getTest
              responses: {}
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        let noResponses = diagnostics.filter { $0.message.contains("no responses") }
        #expect(!noResponses.isEmpty)
    }

    @Test("Undefined security scheme causes parse error or validation error")
    func undefinedSecurityScheme() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "Test"
          version: "1.0"
        security:
          - nonExistentScheme: []
        paths:
          /test:
            get:
              operationId: getTest
              responses:
                "200":
                  description: OK
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        // OpenAPIKit may reject at parse time or our validator catches it
        let errors = diagnostics.filter { $0.severity == .error }
        #expect(!errors.isEmpty)
    }

    @Test("Detects duplicate operationId")
    func duplicateOperationId() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "Test"
          version: "1.0"
        paths:
          /a:
            get:
              operationId: sameId
              responses:
                "200":
                  description: OK
          /b:
            get:
              operationId: sameId
              responses:
                "200":
                  description: OK
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        let dupErrors = diagnostics.filter { $0.message.contains("Duplicate operationId") }
        #expect(!dupErrors.isEmpty)
        #expect(dupErrors.first?.severity == .error)
    }

    @Test("Detects path with no operations")
    func pathNoOperations() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "Test"
          version: "1.0"
        paths:
          /empty: {}
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        let noOps = diagnostics.filter { $0.message.contains("no operations") }
        #expect(!noOps.isEmpty)
    }

    @Test("Operation-level undefined security scheme causes error")
    func operationLevelUndefinedScheme() {
        let yaml = """
        openapi: "3.0.0"
        info:
          title: "Test"
          version: "1.0"
        paths:
          /secured:
            get:
              operationId: getSecured
              security:
                - missingScheme: []
              responses:
                "200":
                  description: OK
        """.data(using: .utf8)!
        let diagnostics = validator.validate(data: yaml)
        // OpenAPIKit may reject at parse time or our validator catches it
        let errors = diagnostics.filter { $0.severity == .error }
        #expect(!errors.isEmpty)
    }

    @Test("ValidationDiagnostic has all fields")
    func diagnosticFields() throws {
        let data = try loadFixture("petstore-validation-issues.yaml")
        let diagnostics = validator.validate(data: data)
        // Check that diagnostics have the expected fields
        let withFile = diagnostics.filter { $0.file != nil }
        #expect(!withFile.isEmpty)
        let withField = diagnostics.filter { $0.field != nil }
        #expect(!withField.isEmpty)
    }
}
