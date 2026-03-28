import Foundation

struct APIResponse {
    let statusCode: Int
    let headers: [String: String]
    let body: String
}

struct TokenResponse {
    let accessToken: String
    let rawBody: String
}

enum SampleVariant: String, CaseIterable, Identifiable {
    case `default` = "default"
    case error500 = "error-500"
    case error503 = "error-503"

    var id: String { rawValue }

    var headerValue: String? {
        switch self {
        case .default:
            return nil
        case .error500, .error503:
            return rawValue
        }
    }
}

struct MoqServerClient {
    func get(
        baseURL: String,
        path: String,
        variant: SampleVariant,
        bearerToken: String? = nil
    ) async throws -> APIResponse {
        guard let url = URL(string: baseURL + path) else {
            throw URLError(.badURL)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        if let variantHeader = variant.headerValue {
            request.setValue(variantHeader, forHTTPHeaderField: "X-Mock-Variant")
        }

        if let bearerToken, !bearerToken.isEmpty {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }

        return try await execute(request)
    }

    func requestClientCredentialsToken(
        baseURL: String,
        clientId: String,
        clientSecret: String
    ) async throws -> TokenResponse {
        guard let url = URL(string: baseURL + "/_auth/token") else {
            throw URLError(.badURL)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")

        let form = "grant_type=client_credentials&client_id=\(urlEncode(clientId))&client_secret=\(urlEncode(clientSecret))"
        request.httpBody = Data(form.utf8)

        let response = try await execute(request)
        let token = parseAccessToken(from: response.body)

        return TokenResponse(accessToken: token ?? "", rawBody: response.body)
    }

    private func execute(_ request: URLRequest) async throws -> APIResponse {
        let (data, urlResponse) = try await URLSession.shared.data(for: request)
        guard let httpResponse = urlResponse as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }

        let body = String(data: data, encoding: .utf8) ?? "<non-utf8-body>"
        let headers = httpResponse.allHeaderFields.reduce(into: [String: String]()) { result, element in
            result[String(describing: element.key)] = String(describing: element.value)
        }

        return APIResponse(
            statusCode: httpResponse.statusCode,
            headers: headers,
            body: body
        )
    }

    private func parseAccessToken(from body: String) -> String? {
        guard let data = body.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = object["access_token"] as? String else {
            return nil
        }
        return token
    }

    private func urlEncode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
    }
}
