import SwiftUI

@MainActor
final class ShowcaseViewModel: ObservableObject {
    @Published var baseURL: String = "http://127.0.0.1:8080"
    @Published var variant: SampleVariant = .default
    @Published var clientId: String = "sample-client"
    @Published var clientSecret: String = "sample-secret"
    @Published var oauthToken: String = ""
    @Published var isLoading: Bool = false
    @Published var output: String = "Ready. Start moqserver and make a request."

    private let client = MoqServerClient()

    func fetchHealth() async {
        await runRequest("GET /public/health") {
            try await client.get(baseURL: normalizedBaseURL, path: "/public/health", variant: .default)
        }
    }

    func fetchPets() async {
        await runRequest("GET /pets") {
            try await client.get(baseURL: normalizedBaseURL, path: "/pets", variant: variant)
        }
    }

    func fetchFavorites() async {
        await runRequest("GET /pets/favorites") {
            try await client.get(
                baseURL: normalizedBaseURL,
                path: "/pets/favorites",
                variant: .default,
                bearerToken: oauthToken
            )
        }
    }

    func requestToken() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let tokenResponse = try await client.requestClientCredentialsToken(
                baseURL: normalizedBaseURL,
                clientId: clientId,
                clientSecret: clientSecret
            )

            oauthToken = tokenResponse.accessToken
            output = """
            Request: POST /_auth/token
            Result: token received
            Access token: \(oauthToken.isEmpty ? "<none returned>" : oauthToken)

            Raw response:
            \(tokenResponse.rawBody)
            """
        } catch {
            output = "Token request failed:\n\(error.localizedDescription)"
        }
    }

    private var normalizedBaseURL: String {
        baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL
    }

    private func runRequest(
        _ label: String,
        execute: () async throws -> APIResponse
    ) async {
        isLoading = true
        defer { isLoading = false }

        do {
            let response = try await execute()
            output = """
            Request: \(label)
            Variant: \(variant.rawValue)
            Status: \(response.statusCode)
            Headers: \(response.headers)

            Body:
            \(response.body)
            """
        } catch {
            output = "\(label) failed:\n\(error.localizedDescription)"
        }
    }
}

struct ContentView: View {
    @StateObject private var model = ShowcaseViewModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Base URL", text: $model.baseURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Picker("Variant for /pets", selection: $model.variant) {
                        ForEach(SampleVariant.allCases) { variant in
                            Text(variant.rawValue).tag(variant)
                        }
                    }
                }

                Section("OAuth") {
                    TextField("Client ID", text: $model.clientId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    SecureField("Client Secret", text: $model.clientSecret)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    TextField("Access Token", text: $model.oauthToken)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Button("Request Token (client_credentials)") {
                        Task { await model.requestToken() }
                    }
                }

                Section("Requests") {
                    Button("GET /public/health") {
                        Task { await model.fetchHealth() }
                    }

                    Button("GET /pets") {
                        Task { await model.fetchPets() }
                    }

                    Button("GET /pets/favorites (Bearer)") {
                        Task { await model.fetchFavorites() }
                    }
                }

                Section("Response") {
                    if model.isLoading {
                        ProgressView()
                    }

                    ScrollView {
                        Text(model.output)
                            .font(.system(.footnote, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    .frame(minHeight: 180)
                }
            }
            .navigationTitle("moqserver Demo")
        }
    }
}
