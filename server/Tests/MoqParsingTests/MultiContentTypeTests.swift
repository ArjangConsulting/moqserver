import Foundation
import Testing
@testable import MoqCore
@testable import MoqParsing

@Suite("Multi-Content-Type Parsing")
struct MultiContentTypeTests {
    let parser = OpenAPIParser()

    func loadFixture(_ name: String) throws -> Data {
        let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!
        return try Data(contentsOf: url)
    }

    @Test("Creates separate variants per content type")
    func multipleVariantsPerContentType() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        #expect(getPets != nil)

        // Should have JSON, XML, and text variants for the 200 response
        let variants = getPets!.responses
        let contentTypes = variants.compactMap { resp in
            resp.headers.first(where: { $0.0 == "Content-Type" })?.1
        }
        #expect(contentTypes.contains("application/json"))
        #expect(contentTypes.contains("application/xml"))
        #expect(contentTypes.contains("text/plain"))
    }

    @Test("JSON variant is first (preferred)")
    func jsonVariantFirst() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        #expect(getPets != nil)

        let firstVariant = getPets!.responses.first
        let firstContentType = firstVariant?.headers.first(where: { $0.0 == "Content-Type" })?.1
        #expect(firstContentType == "application/json")
    }

    @Test("XML variant has XML body")
    func xmlVariantBody() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        #expect(getPets != nil)

        let xmlVariant = getPets!.responses.first { resp in
            resp.headers.contains(where: { $0.0 == "Content-Type" && $0.1 == "application/xml" })
        }
        #expect(xmlVariant != nil)

        if let body = xmlVariant?.body, let text = String(data: body, encoding: .utf8) {
            #expect(text.contains("<?xml"))
        } else {
            Issue.record("XML variant should have body")
        }
    }

    @Test("Image endpoint generates binary placeholder")
    func imagePlaceholder() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getPhoto = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/{petId}/photo" }
        #expect(getPhoto != nil)

        let pngVariant = getPhoto!.responses.first { resp in
            resp.headers.contains(where: { $0.0 == "Content-Type" && $0.1 == "image/png" })
        }
        #expect(pngVariant != nil)
        #expect(pngVariant?.body != nil)
        // PNG starts with magic bytes
        if let body = pngVariant?.body {
            #expect(body.count > 0)
            #expect(body[0] == 0x89)
            #expect(body[1] == 0x50)  // 'P'
        }
    }

    @Test("SVG endpoint generates SVG placeholder")
    func svgPlaceholder() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getPhoto = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/{petId}/photo" }
        #expect(getPhoto != nil)

        let svgVariant = getPhoto!.responses.first { resp in
            resp.headers.contains(where: { $0.0 == "Content-Type" && $0.1 == "image/svg+xml" })
        }
        #expect(svgVariant != nil)
        if let body = svgVariant?.body, let text = String(data: body, encoding: .utf8) {
            #expect(text.contains("<svg"))
        }
    }

    @Test("PDF endpoint generates PDF placeholder")
    func pdfPlaceholder() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getReport = spec.endpoints.first { $0.method == "GET" && $0.path == "/report" }
        #expect(getReport != nil)

        let pdfVariant = getReport!.responses.first { resp in
            resp.headers.contains(where: { $0.0 == "Content-Type" && $0.1 == "application/pdf" })
        }
        #expect(pdfVariant != nil)
        if let body = pdfVariant?.body, let text = String(data: body, encoding: .utf8) {
            #expect(text.hasPrefix("%PDF"))
        }
    }

    @Test("CSV endpoint generates CSV placeholder")
    func csvPlaceholder() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getReport = spec.endpoints.first { $0.method == "GET" && $0.path == "/report" }
        #expect(getReport != nil)

        let csvVariant = getReport!.responses.first { resp in
            resp.headers.contains(where: { $0.0 == "Content-Type" && $0.1 == "text/csv" })
        }
        #expect(csvVariant != nil)
        if let body = csvVariant?.body, let text = String(data: body, encoding: .utf8) {
            #expect(text.contains(","))
        }
    }

    @Test("HTML variant generates HTML stub from schema")
    func htmlStub() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getPet = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/{petId}" }
        #expect(getPet != nil)

        let htmlVariant = getPet!.responses.first { resp in
            resp.headers.contains(where: { $0.0 == "Content-Type" && $0.1 == "text/html" })
        }
        #expect(htmlVariant != nil)
        if let body = htmlVariant?.body, let text = String(data: body, encoding: .utf8) {
            #expect(text.contains("<!DOCTYPE html>"))
            #expect(text.contains("<table>"))
        }
    }

    @Test("XML example encoding preserves data")
    func xmlExampleEncoding() throws {
        let data = try loadFixture("petstore-multi-content.yaml")
        let spec = try parser.parse(data: data)

        let getPet = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/{petId}" }
        #expect(getPet != nil)

        let xmlVariant = getPet!.responses.first { resp in
            resp.headers.contains(where: { $0.0 == "Content-Type" && $0.1 == "application/xml" })
        }
        #expect(xmlVariant != nil)
        if let body = xmlVariant?.body, let text = String(data: body, encoding: .utf8) {
            #expect(text.contains("<?xml"))
            #expect(text.contains("Fido"))
        }
    }
}
