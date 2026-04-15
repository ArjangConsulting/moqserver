package com.moqserver.studio.projectformat

import com.moqserver.studio.logging.loggerFor
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class YamlProjectCodec {
    private val logger = loggerFor<YamlProjectCodec>()
    private val loader = Load(LoadSettings.builder().build())

    fun parseDocument(source: String): Any? {
        return loader.loadFromString(source)
    }

    fun decodeManifest(yaml: String): ProjectManifest {
        logger.debug("Decoding manifest ({} bytes)", yaml.length)
        val map = loader.loadFromString(yaml) as? Map<*, *>
            ?: throw IllegalArgumentException("${MoqProjectFormat.MANIFEST_FILE} must be a YAML mapping")
        return parseManifest(map)
    }

    fun decodeEndpoint(yaml: String): EndpointDocument {
        logger.debug("Decoding endpoint ({} bytes)", yaml.length)
        val map = loader.loadFromString(yaml) as? Map<*, *>
            ?: throw IllegalArgumentException("Endpoint file must be a YAML mapping")
        return parseEndpoint(map)
    }

    private fun parseManifest(map: Map<*, *>): ProjectManifest {
        return ProjectManifest(
            version = map.str("version") ?: "1",
            name = map.str("name") ?: throw missing("name", MoqProjectFormat.MANIFEST_FILE),
            description = map.str("description"),
            defaults = (map["defaults"] as? Map<*, *>)?.let { parseDefaults(it) }
                ?: throw missing("defaults", MoqProjectFormat.MANIFEST_FILE),
            globalRules = (map["global_rules"] as? Map<*, *>)?.let { parseGlobalRules(it) },
        )
    }

    private fun parseDefaults(map: Map<*, *>): ProjectDefaults {
        return ProjectDefaults(
            delayMs = map.int("delay_ms") ?: 0,
            auth = (map["auth"] as? Map<*, *>)?.let { parseAuth(it) }
                ?: ProjectAuthConfig(AuthType.NONE, verify = false),
            network = (map["network"] as? Map<*, *>)?.let { parseNetwork(it) }
                ?: NetworkBehavior(),
        )
    }

    private fun parseAuth(map: Map<*, *>): ProjectAuthConfig {
        val typeStr = map.str("type") ?: "none"
        val type = when (typeStr) {
            "none" -> AuthType.NONE
            "bearer" -> AuthType.BEARER
            "basic" -> AuthType.BASIC
            "api-key" -> AuthType.API_KEY
            "header" -> AuthType.HEADER
            else -> AuthType.NONE
        }
        return ProjectAuthConfig(
            type = type,
            verify = map.bool("verify") ?: false,
            headerName = map.str("header_name"),
        )
    }

    private fun parseNetwork(map: Map<*, *>): NetworkBehavior {
        return NetworkBehavior(
            latencyMs = map.int("latency_ms"),
            jitterMs = map.int("jitter_ms"),
            packetLossPercent = map.double("packet_loss_percent"),
        )
    }

    private fun parseGlobalRules(map: Map<*, *>): GlobalRules {
        return GlobalRules(
            requiredHeaders = (map["required_headers"] as? List<*>)?.map { parseRuleMatcher(it as Map<*, *>) },
            verifyCookies = map.bool("verify_cookies"),
        )
    }

    private fun parseRuleMatcher(map: Map<*, *>): RuleMatcher {
        return RuleMatcher(
            name = map.str("name") ?: throw missing("name", "rule_matcher"),
            match = map.str("match"),
            required = map.bool("required"),
            matchType = map.str("match_type")?.let(::parseMatchType),
        )
    }

    private fun parseMatchType(value: String): MatchType? {
        return when (value) {
            "require" -> MatchType.REQUIRE
            "equal_to" -> MatchType.EQUAL_TO
            "not_equal_to" -> MatchType.NOT_EQUAL_TO
            "contains" -> MatchType.CONTAINS
            "not_contains" -> MatchType.NOT_CONTAINS
            "begins_with" -> MatchType.BEGINS_WITH
            "ends_with" -> MatchType.ENDS_WITH
            "matches_regex" -> MatchType.MATCHES_REGEX
            "is_empty" -> MatchType.IS_EMPTY
            "not_empty" -> MatchType.NOT_EMPTY
            "gt" -> MatchType.GT
            "gte" -> MatchType.GTE
            "lt" -> MatchType.LT
            "lte" -> MatchType.LTE
            else -> null
        }
    }

    private fun encodeMatchType(matchType: MatchType): String {
        return when (matchType) {
            MatchType.REQUIRE -> "require"
            MatchType.EQUAL_TO -> "equal_to"
            MatchType.NOT_EQUAL_TO -> "not_equal_to"
            MatchType.CONTAINS -> "contains"
            MatchType.NOT_CONTAINS -> "not_contains"
            MatchType.BEGINS_WITH -> "begins_with"
            MatchType.ENDS_WITH -> "ends_with"
            MatchType.MATCHES_REGEX -> "matches_regex"
            MatchType.IS_EMPTY -> "is_empty"
            MatchType.NOT_EMPTY -> "not_empty"
            MatchType.GT -> "gt"
            MatchType.GTE -> "gte"
            MatchType.LT -> "lt"
            MatchType.LTE -> "lte"
        }
    }

    private fun parseEndpoint(map: Map<*, *>): EndpointDocument {
        val id = map.str("id") ?: throw missing("id", "endpoint")
        val method = map.str("method") ?: throw missing("method", "endpoint")
        val path = map.str("path") ?: throw missing("path", "endpoint")
        val operation = (map["operation"] as? Map<*, *>)?.let { parseOperation(it) }

        return EndpointDocument(
            id = id,
            alias = map.str("alias")?.takeIf { it.isNotBlank() }
                ?: defaultAliasForEndpoint(method = method, path = path, operation = operation),
            description = map.str("description")?.takeIf { it.isNotBlank() },
            referenceName = map.str("reference_name")?.takeIf { it.isNotBlank() }
                ?: defaultReferenceNameForEndpointId(id),
            method = method,
            path = path,
            tags = (map["tags"] as? List<*>)?.map { it.toString() },
            auth = (map["auth"] as? Map<*, *>)?.let { parseAuth(it) },
            requestRules = (map["request_rules"] as? Map<*, *>)?.let { parseRequestRules(it) },
            operation = operation,
            network = (map["network"] as? Map<*, *>)?.let { parseNetwork(it) },
            variants = (map["variants"] as? List<*>)?.map { parseVariant(it as Map<*, *>) }
                ?: throw missing("variants", "endpoint"),
        )
    }

    private fun parseRequestRules(map: Map<*, *>): RequestRules {
        return RequestRules(
            headers = (map["headers"] as? List<*>)?.map { parseRuleMatcher(it as Map<*, *>) },
            verifyCookies = map.bool("verify_cookies"),
            queryParams = (map["query_params"] as? List<*>)?.map { parseRuleMatcher(it as Map<*, *>) },
            cookies = (map["cookies"] as? List<*>)?.map { parseRuleMatcher(it as Map<*, *>) },
        )
    }

    private fun parseOperation(map: Map<*, *>): EndpointOperation {
        val typeStr = map.str("type") ?: "query"
        val type = when (typeStr) {
            "query" -> OperationType.QUERY
            "mutation" -> OperationType.MUTATION
            "subscription" -> OperationType.SUBSCRIPTION
            else -> OperationType.QUERY
        }
        return EndpointOperation(
            type = type,
            name = map.str("name"),
            document = map.str("document"),
        )
    }

    private fun parseVariant(map: Map<*, *>): ProjectVariant {
        val name = map.str("name") ?: throw missing("name", "variant")
        return ProjectVariant(
            name = name,
            referenceName = map.str("reference_name")?.takeIf { it.isNotBlank() }
                ?: defaultReferenceNameForVariantName(name),
            description = map.str("description")?.takeIf { it.isNotBlank() },
            isDefault = map.bool("default"),
            status = map.int("status") ?: throw missing("status", "variant"),
            headers = (map["headers"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() },
            requestMatch = (map["request_match"] as? Map<*, *>)?.let { parseVariantRequestMatch(it) },
            body = map["body"]?.let { YamlValue.from(it) },
            bodyFile = map.str("body_file"),
            delayMs = map.int("delay_ms"),
        )
    }

    private fun parseVariantRequestMatch(map: Map<*, *>): VariantRequestMatch {
        return VariantRequestMatch(
            query = (map["query"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() },
            headers = (map["headers"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() },
            bodyContains = map.str("body_contains")?.takeIf { it.isNotBlank() },
        )
    }

    fun encodeManifest(manifest: ProjectManifest): String {
        logger.debug("Encoding manifest: {}", manifest.name)
        val lines = mutableListOf<String>()
        lines += """version: "${manifest.version}""""
        lines += "name: ${yamlQuote(manifest.name)}"
        manifest.description?.let { lines += "description: ${yamlQuote(it)}" }
        lines += ""
        lines += "defaults:"
        lines += "  delay_ms: ${manifest.defaults.delayMs}"
        lines += "  auth:"
        lines += encodeAuth(manifest.defaults.auth, indent = 4)
        lines += "  network:"
        lines += encodeNetwork(manifest.defaults.network, indent = 4)

        manifest.globalRules?.let { rules ->
            lines += ""
            lines += "global_rules:"
            val headers = rules.requiredHeaders
            if (!headers.isNullOrEmpty()) {
                lines += "  required_headers:"
                headers.forEach { lines += encodeRuleMatcher(it, indent = 4) }
            } else {
                lines += "  required_headers: []"
            }
            lines += "  verify_cookies: ${rules.verifyCookies ?: false}"
        }

        return lines.joinToString("\n") + "\n"
    }

    fun encodeEndpoint(endpoint: EndpointDocument): String {
        logger.debug("Encoding endpoint: {} {}", endpoint.method, endpoint.path)
        val lines = mutableListOf<String>()

        lines += "id: ${yamlQuote(endpoint.id)}"
        lines += "alias: ${yamlQuote(endpoint.displayAlias)}"
        endpoint.description?.let { lines += "description: ${yamlQuote(it)}" }
        lines += "reference_name: ${yamlQuote(endpoint.referenceName)}"
        lines += "method: ${yamlQuote(endpoint.method)}"
        lines += "path: ${yamlQuote(endpoint.path)}"
        endpoint.tags?.takeIf { it.isNotEmpty() }?.let {
            lines += "tags: [${it.joinToString(", ") { tag -> yamlQuote(tag) }}]"
        }

        endpoint.operation?.let { op ->
            lines += ""
            lines += "operation:"
            lines += "  type: ${op.type.name.lowercase()}"
            op.name?.let { lines += "  name: ${yamlQuote(it)}" }
            op.document?.let { doc ->
                lines += "  document: |"
                doc.split("\n").forEach { docLine ->
                    lines += "    $docLine"
                }
            }
        }

        endpoint.auth?.let { auth ->
            lines += ""
            lines += "auth:"
            lines += encodeAuth(auth, indent = 2)
        }

        endpoint.requestRules?.let { rules ->
            lines += ""
            lines += "request_rules:"
            rules.headers?.takeIf { it.isNotEmpty() }?.let { headers ->
                lines += "  headers:"
                headers.forEach { lines += encodeRuleMatcher(it, indent = 4) }
            }
            rules.verifyCookies?.let { lines += "  verify_cookies: $it" }
            val qp = rules.queryParams
            if (qp != null && qp.isNotEmpty()) {
                lines += "  query_params:"
                qp.forEach { lines += encodeRuleMatcher(it, indent = 4) }
            } else if (qp != null) {
                lines += "  query_params: []"
            }
            val cookies = rules.cookies
            if (cookies != null && cookies.isNotEmpty()) {
                lines += "  cookies:"
                cookies.forEach { lines += encodeRuleMatcher(it, indent = 4) }
            } else if (cookies != null) {
                lines += "  cookies: []"
            }
        }

        lines += ""
        lines += "variants:"
        endpoint.variants.forEach { lines += encodeVariant(it, indent = 2) }

        endpoint.network?.let { net ->
            lines += ""
            lines += "network:"
            lines += encodeNetwork(net, indent = 2)
        }

        return lines.joinToString("\n") + "\n"
    }

    private fun encodeVariant(variant: ProjectVariant, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        val lines = mutableListOf<String>()

        lines += "${pad}- name: ${yamlQuote(variant.name)}"
        lines += "${pad}  reference_name: ${yamlQuote(variant.referenceName)}"
        variant.description?.let { lines += "${pad}  description: ${yamlQuote(it)}" }
        if (variant.isDefault == true) {
            lines += "${pad}  default: true"
        }
        lines += "${pad}  status: ${variant.status}"

        variant.headers?.takeIf { it.isNotEmpty() }?.let { headers ->
            lines += encodeVariantStringMap(headers = headers, sectionName = "headers", indent = indent + 2)
        }
        lines += encodeVariantRequestMatch(variant.requestMatch, indent)
        lines += encodeVariantBody(variant, indent)

        variant.delayMs?.let { lines += "${pad}  delay_ms: $it" }

        return lines
    }

    private fun encodeVariantRequestMatch(requestMatch: VariantRequestMatch?, indent: Int): List<String> {
        if (requestMatch == null || requestMatch.isEmpty()) {
            return emptyList()
        }

        val pad = " ".repeat(indent)
        val lines = mutableListOf<String>()
        lines += "${pad}  request_match:"
        requestMatch.query?.takeIf { it.isNotEmpty() }?.let { query ->
            lines += encodeVariantStringMap(headers = query, sectionName = "query", indent = indent + 4)
        }
        requestMatch.headers?.takeIf { it.isNotEmpty() }?.let { headers ->
            lines += encodeVariantStringMap(headers = headers, sectionName = "headers", indent = indent + 4)
        }
        requestMatch.bodyContains?.takeIf { it.isNotBlank() }?.let { bodyContains ->
            lines += "${pad}    body_contains: ${yamlQuote(bodyContains)}"
        }
        return lines
    }

    private fun encodeVariantStringMap(headers: Map<String, String>, sectionName: String, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return buildList {
            add("$pad$sectionName:")
            headers.toSortedMap().forEach { (key, value) ->
                add("${pad}  ${encodeYamlKey(key)}: ${yamlQuote(value)}")
            }
        }
    }

    private fun encodeVariantBody(variant: ProjectVariant, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        variant.bodyFile?.let { bodyFile ->
            return listOf("${pad}  body_file: ${yamlQuote(bodyFile)}")
        }

        return when (val body = variant.body) {
            null -> emptyList()
            is YamlValue.Null,
            is YamlValue.Bool,
            is YamlValue.Int,
            is YamlValue.Double,
            -> listOf("${pad}  body: ${encodeInlineBodyValue(body)}")
            is YamlValue.Str -> encodeVariantStringBody(body, indent)
            is YamlValue.Array -> encodeVariantCollectionBody(body, emptyValue = "[]", indent = indent)
            is YamlValue.Obj -> encodeVariantCollectionBody(body, emptyValue = "{}", indent = indent)
        }
    }

    private fun encodeVariantStringBody(body: YamlValue.Str, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return if (body.value.contains('\n')) {
            listOf("${pad}  body: |-") + encodeMultilineStringLines(body.value, indent + 4)
        } else {
            listOf("${pad}  body: ${encodeInlineBodyValue(body)}")
        }
    }

    private fun encodeVariantCollectionBody(body: YamlValue, emptyValue: String, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return if (body.isInlineBodyValue()) {
            listOf("${pad}  body: $emptyValue")
        } else {
            listOf("${pad}  body:") + encodeBlockBodyLines(body, indent + 4)
        }
    }

    private fun encodeAuth(auth: ProjectAuthConfig, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        val typeName = when (auth.type) {
            AuthType.NONE -> "none"
            AuthType.BEARER -> "bearer"
            AuthType.BASIC -> "basic"
            AuthType.API_KEY -> "api-key"
            AuthType.HEADER -> "header"
        }
        return listOf(
            "${pad}type: $typeName",
            "${pad}verify: ${auth.verify}",
            "${pad}header_name: ${auth.headerName?.let(::yamlQuote) ?: "null"}",
        )
    }

    private fun encodeNetwork(network: NetworkBehavior, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return listOf(
            "${pad}latency_ms: ${network.latencyMs ?: 0}",
            "${pad}jitter_ms: ${network.jitterMs ?: 0}",
            "${pad}packet_loss_percent: ${network.packetLossPercent ?: 0}",
        )
    }

    private fun encodeRuleMatcher(matcher: RuleMatcher, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        val lines = mutableListOf<String>()
        lines += "${pad}- name: ${yamlQuote(matcher.name)}"
        matcher.matchType?.let { lines += "${pad}  match_type: ${encodeMatchType(it)}" }
        matcher.match?.let { lines += "${pad}  match: ${yamlQuote(it)}" }
        matcher.required?.let { lines += "${pad}  required: $it" }
        return lines
    }

    private fun encodeInlineBodyValue(value: YamlValue): String = when (value) {
        is YamlValue.Null -> "null"
        is YamlValue.Bool -> if (value.value) "true" else "false"
        is YamlValue.Int -> "${value.value}"
        is YamlValue.Double -> "${value.value}"
        is YamlValue.Str -> yamlQuote(value.value)
        is YamlValue.Array -> if (value.value.isEmpty()) "[]" else error("Non-empty arrays must be block-encoded")
        is YamlValue.Obj -> if (value.value.isEmpty()) "{}" else error("Non-empty objects must be block-encoded")
    }

    private fun encodeBlockBodyLines(value: YamlValue, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return when (value) {
            is YamlValue.Null,
            is YamlValue.Bool,
            is YamlValue.Int,
            is YamlValue.Double,
            -> listOf("$pad${encodeInlineBodyValue(value)}")
            is YamlValue.Str -> {
                if (value.value.contains('\n')) {
                    listOf("${pad}|-") + encodeMultilineStringLines(value.value, indent + 2)
                } else {
                    listOf("$pad${encodeInlineBodyValue(value)}")
                }
            }
            is YamlValue.Array -> {
                if (value.value.isEmpty()) {
                    listOf("${pad}[]")
                } else {
                    value.value.flatMap { item ->
                        if (item is YamlValue.Str && item.value.contains('\n')) {
                            listOf("$pad- |-") + encodeMultilineStringLines(item.value, indent + 2)
                        } else if (item.isInlineBodyValue()) {
                            listOf("$pad- ${encodeInlineBodyValue(item)}")
                        } else {
                            listOf("$pad-") + encodeBlockBodyLines(item, indent + 2)
                        }
                    }
                }
            }
            is YamlValue.Obj -> {
                if (value.value.isEmpty()) {
                    listOf("${pad}{}")
                } else {
                    value.value.toSortedMap().flatMap { (key, item) ->
                        val yamlKey = encodeYamlKey(key)
                        if (item is YamlValue.Str && item.value.contains('\n')) {
                            listOf("$pad$yamlKey: |-") + encodeMultilineStringLines(item.value, indent + 2)
                        } else if (item.isInlineBodyValue()) {
                            listOf("$pad$yamlKey: ${encodeInlineBodyValue(item)}")
                        } else {
                            listOf("$pad$yamlKey:") + encodeBlockBodyLines(item, indent + 2)
                        }
                    }
                }
            }
        }
    }

    private fun yamlQuote(string: String): String {
        val escaped = buildString(string.length) {
            string.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code in 0x00..0x1F || ch == '\u007F') {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }
        return "\"$escaped\""
    }

    private fun encodeYamlKey(key: String): String {
        return if (SAFE_YAML_KEY.matches(key)) key else yamlQuote(key)
    }

    private fun encodeMultilineStringLines(value: String, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { line -> "$pad$line" }
    }

    private fun missing(field: String, context: String) =
        IllegalArgumentException("Missing required field '$field' in $context")
}

private val SAFE_YAML_KEY = Regex("^[A-Za-z0-9_-]+$")

private fun VariantRequestMatch.isEmpty(): Boolean {
    return query.isNullOrEmpty() && headers.isNullOrEmpty() && bodyContains.isNullOrBlank()
}

private fun YamlValue.isInlineBodyValue(): Boolean = when (this) {
    is YamlValue.Null,
    is YamlValue.Bool,
    is YamlValue.Int,
    is YamlValue.Double,
    -> true
    is YamlValue.Str -> !value.contains('\n') && !value.contains('\r')
    is YamlValue.Array -> value.isEmpty()
    is YamlValue.Obj -> value.isEmpty()
}

private fun Map<*, *>.str(key: String): String? = this[key]?.toString()?.takeIf { it != "null" }
private fun Map<*, *>.int(key: String): Int? = when (val v = this[key]) {
    is Int -> v
    is Long -> v.toInt()
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}
private fun Map<*, *>.double(key: String): Double? = when (val v = this[key]) {
    is Double -> v
    is Float -> v.toDouble()
    is Int -> v.toDouble()
    is Long -> v.toDouble()
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}
private fun Map<*, *>.bool(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.toBooleanStrictOrNull()
    else -> null
}
