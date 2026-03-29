/// Match behavior for request validation rules.
public enum MatchType: String, Codable, Sendable, Equatable {
    case require = "require"
    case equalTo = "equal_to"
    case notEqualTo = "not_equal_to"
    case contains = "contains"
    case notContains = "not_contains"
    case beginsWith = "begins_with"
    case endsWith = "ends_with"
    case matchesRegex = "matches_regex"
    case isEmpty = "is_empty"
    case notEmpty = "not_empty"
    case gt = "gt"
    case gte = "gte"
    case lt = "lt"
    case lte = "lte"
}
