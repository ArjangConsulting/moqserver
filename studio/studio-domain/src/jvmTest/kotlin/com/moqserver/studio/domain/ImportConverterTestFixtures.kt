package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue

internal fun makeProject(vararg endpoints: EndpointDocument, path: String = "/tmp/project"): MoqProject =
	MoqProject(
		manifest = ProjectManifest(
			version = "1",
			name = "Test Project",
			defaults = ProjectDefaults(
				delayMs = 0,
				auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
				network = NetworkBehavior(),
			),
		),
		endpoints = endpoints.toList(),
		projectPath = path,
	)

internal fun makeEndpoint(
	method: String = "GET",
	path: String = "/items",
	statusCodes: List<Int> = listOf(200),
	authType: AuthType = AuthType.NONE,
	requiredHeaders: List<String> = emptyList(),
	tags: List<String>? = null,
	userBody: YamlValue? = YamlValue.Obj(mapOf("spec" to YamlValue.Str("value"))),
): EndpointDocument {
	val id = ImportConverter.endpointId(method, path)
	return EndpointDocument(
		id = id,
		alias = "Alias",
		referenceName = "alias",
		method = method.uppercase(),
		path = path,
		tags = tags,
		auth = if (authType != AuthType.NONE) ProjectAuthConfig(authType, verify = true) else null,
		requestRules = requiredHeaders.takeIf { it.isNotEmpty() }?.let {
			RequestRules(headers = it.map { header -> RuleMatcher(name = header, required = true) })
		},
		variants = statusCodes.map { code ->
			ProjectVariant(
				name = "variant-$code",
				referenceName = "variant$code",
				status = code,
				body = userBody,
			)
		},
	)
}

internal fun parsedEndpoint(
	method: String = "GET",
	path: String = "/items",
	statusCodes: List<Int> = listOf(200),
	authType: AuthType = AuthType.NONE,
	requiredHeaders: List<String> = emptyList(),
	tags: List<String> = emptyList(),
): ParsedEndpoint = ParsedEndpoint(
	method = method,
	path = path,
	responses = statusCodes.map { code ->
		ParsedResponse(name = "resp-$code", statusCode = code, body = """{"spec":"value"}""")
	},
	authType = authType,
	requiredHeaders = requiredHeaders,
	tags = tags,
)
