package com.moqserver.studio.projectformat

import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointAliasesTest {

	// ── defaultAliasForEndpoint ─────────────────────────────────────

	@Test
	fun `GET with collection path generates List verb`() {
		val alias = defaultAliasForEndpoint("GET", "/pets")
		assertEquals("List Pets", alias)
	}

	@Test
	fun `GET with path parameter generates Get verb with By clause`() {
		val alias = defaultAliasForEndpoint("GET", "/pets/{petId}")
		assertEquals("Get Pets By Pet Id", alias)
	}

	@Test
	fun `POST generates Create verb`() {
		val alias = defaultAliasForEndpoint("POST", "/users")
		assertEquals("Create Users", alias)
	}

	@Test
	fun `PUT generates Update verb`() {
		val alias = defaultAliasForEndpoint("PUT", "/users/{userId}")
		assertEquals("Update Users By User Id", alias)
	}

	@Test
	fun `PATCH generates Update verb`() {
		val alias = defaultAliasForEndpoint("PATCH", "/users/{userId}")
		assertEquals("Update Users By User Id", alias)
	}

	@Test
	fun `DELETE generates Delete verb`() {
		val alias = defaultAliasForEndpoint("DELETE", "/users/{userId}")
		assertEquals("Delete Users By User Id", alias)
	}

	@Test
	fun `HEAD generates Head verb`() {
		val alias = defaultAliasForEndpoint("HEAD", "/status")
		assertEquals("Head Status", alias)
	}

	@Test
	fun `OPTIONS generates Options verb`() {
		val alias = defaultAliasForEndpoint("OPTIONS", "/config")
		assertEquals("Options Config", alias)
	}

	@Test
	fun `api and version segments are ignored`() {
		val alias = defaultAliasForEndpoint("GET", "/api/v2/users")
		assertEquals("List Users", alias)
	}

	@Test
	fun `root path generates Root label`() {
		val alias = defaultAliasForEndpoint("GET", "/")
		assertEquals("List Root", alias)
	}

	@Test
	fun `camelCase path segments are tokenized`() {
		val alias = defaultAliasForEndpoint("GET", "/userProfiles")
		assertEquals("List User Profiles", alias)
	}

	@Test
	fun `snake_case path segments are tokenized`() {
		val alias = defaultAliasForEndpoint("GET", "/user_profiles")
		assertEquals("List User Profiles", alias)
	}

	@Test
	fun `multiple path parameters produce combined By clause`() {
		val alias = defaultAliasForEndpoint("GET", "/teams/{teamId}/members/{memberId}")
		assertEquals("Get Teams Members By Team Id Member Id", alias)
	}

	@Test
	fun `query string is ignored`() {
		val alias = defaultAliasForEndpoint("GET", "/pets?limit=10")
		assertEquals("List Pets", alias)
	}

	// ── GraphQL operations ──────────────────────────────────────────

	@Test
	fun `GraphQL with named operation uses humanized name`() {
		val alias = defaultAliasForEndpoint(
			"POST",
			"/graphql",
			EndpointOperation(type = OperationType.QUERY, name = "getUser"),
		)
		assertEquals("Get User", alias)
	}

	@Test
	fun `GraphQL path without name uses operation type label`() {
		val alias = defaultAliasForEndpoint(
			"POST",
			"/graphql",
			EndpointOperation(type = OperationType.MUTATION),
		)
		assertEquals("Mutation Operation", alias)
	}

	@Test
	fun `GraphQL path without operation info uses default label`() {
		val alias = defaultAliasForEndpoint("POST", "/graphql")
		assertEquals("GraphQL Operation", alias)
	}

	// ── humanizeAliasSource ─────────────────────────────────────────

	@Test
	fun `humanizeAliasSource handles camelCase`() {
		assertEquals("Get User Profile", humanizeAliasSource("getUserProfile"))
	}

	@Test
	fun `humanizeAliasSource handles ALLCAPS when all-uppercase`() {
		assertEquals("HTTP", humanizeAliasSource("HTTP"))
	}

	@Test
	fun `humanizeAliasSource handles underscores and hyphens`() {
		assertEquals("Create New Item", humanizeAliasSource("create_new-item"))
	}

	// ── displayAlias extension ──────────────────────────────────────

	@Test
	fun `displayAlias returns explicit alias when set`() {
		val endpoint = EndpointDocument(
			id = "get-pets",
			alias = "My Custom Alias",
			method = "GET",
			path = "/pets",
			variants = emptyList(),
		)
		assertEquals("My Custom Alias", endpoint.displayAlias)
	}

	@Test
	fun `displayAlias falls back to generated alias when alias is blank`() {
		val endpoint = EndpointDocument(
			id = "get-pets",
			alias = "  ",
			method = "GET",
			path = "/pets",
			variants = emptyList(),
		)
		assertEquals("List Pets", endpoint.displayAlias)
	}

	@Test
	fun `displayAlias falls back to generated alias when alias is null`() {
		val endpoint = EndpointDocument(
			id = "get-pets",
			alias = null,
			method = "GET",
			path = "/pets",
			variants = emptyList(),
		)
		assertEquals("List Pets", endpoint.displayAlias)
	}
}
