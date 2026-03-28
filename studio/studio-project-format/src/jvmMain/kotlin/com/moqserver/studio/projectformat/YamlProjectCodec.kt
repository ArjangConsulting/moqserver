package com.moqserver.studio.projectformat

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class YamlProjectCodec {
    private val loader = Load(LoadSettings.builder().build())

    fun parseDocument(source: String): Any? {
        return loader.loadFromString(source)
    }

    fun decodeManifest(yaml: String): ProjectManifest {
        val map = loader.loadFromString(yaml) as? Map<*, *>
            ?: throw IllegalArgumentException("project.yml must be a YAML mapping")
        return parseManifest(map)
    }

    fun decodeEndpoint(yaml: String): EndpointDocument {
        val map = loader.loadFromString(yaml) as? Map<*, *>
            ?: throw IllegalArgumentException("Endpoint file must be a YAML mapping")
        return parseEndpoint(map)
    }

    private fun parseManifest(map: Map<*, *>): ProjectManifest {
        return ProjectManifest(
            version = map.str("version") ?: "1",
            name = map.str("name") ?: throw missing("name", "project.yml"),
            description = map.str("description"),
            defaults = (map["defaults"] as? Map<*, *>)?.let { parseDefaults(it) }
                ?: throw missing("defaults", "project.yml"),
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
        )
    }

    private fun parseEndpoint(map: Map<*, *>): EndpointDocument {
        return EndpointDocument(
            id = map.str("id") ?: throw missing("id", "endpoint"),
            alias = map.str("alias"),
            method = map.str("method") ?: throw missing("method", "endpoint"),
            path = map.str("path") ?: throw missing("path", "endpoint"),
            tags = (map["tags"] as? List<*>)?.map { it.toString() },
            auth = (map["auth"] as? Map<*, *>)?.let { parseAuth(it) },
            requestRules = (map["request_rules"] as? Map<*, *>)?.let { parseRequestRules(it) },
            operation = (map["operation"] as? Map<*, *>)?.let { parseOperation(it) },
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
        return ProjectVariant(
            name = map.str("name") ?: throw missing("name", "variant"),
            isDefault = map.bool("default"),
            status = map.int("status") ?: throw missing("status", "variant"),
            headers = (map["headers"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() },
            body = map["body"]?.let { YamlValue.from(it) },
            bodyFile = map.str("body_file"),
            delayMs = map.int("delay_ms"),
        )
    }

    fun encodeManifest(manifest: ProjectManifest): String {
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
        val lines = mutableListOf<String>()

        lines += "id: ${endpoint.id}"
        endpoint.alias?.let { lines += "alias: ${yamlQuote(it)}" }
        lines += "method: ${endpoint.method}"
        lines += "path: ${endpoint.path}"
        endpoint.tags?.takeIf { it.isNotEmpty() }?.let {
            lines += "tags: [${it.joinToString(", ")}]"
        }

        endpoint.operation?.let { op ->
            lines += ""
            lines += "operation:"
            lines += "  type: ${op.type.name.lowercase()}"
            op.name?.let { lines += "  name: $it" }
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

        lines += "${pad}- name: ${variant.name}"
        if (variant.isDefault == true) {
            lines += "${pad}  default: true"
        }
        lines += "${pad}  status: ${variant.status}"

        variant.headers?.takeIf { it.isNotEmpty() }?.let { headers ->
            lines += "${pad}  headers:"
            headers.toSortedMap().forEach { (k, v) ->
                lines += "${pad}    $k: $v"
            }
        }

        if (variant.bodyFile != null) {
            lines += "${pad}  body_file: ${variant.bodyFile}"
        } else {
            val body = variant.body
            when (body) {
                null -> { /* no body */ }
                is YamlValue.Null, is YamlValue.Bool, is YamlValue.Int,
                is YamlValue.Double, is YamlValue.Str -> {
                    if (body is YamlValue.Str && body.value.contains('\n')) {
                        lines += "${pad}  body: |-"
                        lines += encodeMultilineStringLines(body.value, indent + 4)
                    } else {
                        lines += "${pad}  body: ${encodeInlineBodyValue(body)}"
                    }
                }
                is YamlValue.Array -> {
                    if (body.value.isEmpty()) {
                        lines += "${pad}  body: []"
                    } else {
                        lines += "${pad}  body:"
                        lines += encodeBlockBodyLines(body, indent + 4)
                    }
                }
                is YamlValue.Obj -> {
                    if (body.value.isEmpty()) {
                        lines += "${pad}  body: {}"
                    } else {
                        lines += "${pad}  body:"
                        lines += encodeBlockBodyLines(body, indent + 4)
                    }
                }
            }
        }

        variant.delayMs?.let { lines += "${pad}  delay_ms: $it" }

        return lines
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
            "${pad}header_name: ${auth.headerName ?: "null"}",
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
        lines += "${pad}- name: ${matcher.name}"
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
        val escaped = string.replace("\\", "\\\\").replace("\"", "\\\"")
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
