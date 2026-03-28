/// Authentication requirement for an endpoint.
public indirect enum AuthRequirement: Sendable, Equatable {
    case none
    case bearer
    case basic
    case apiKey(headerName: String)
    case oauth2(scopes: [String])
    case openIdConnect(scopes: [String])
    case allOf([AuthRequirement])
    case anyOf([AuthRequirement])
}
