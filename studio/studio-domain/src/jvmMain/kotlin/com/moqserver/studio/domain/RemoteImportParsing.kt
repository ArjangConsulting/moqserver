package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.format.RemoteParsedEndpoint
import com.moqserver.studio.projectformat.format.RemoteParsedResponse
import com.moqserver.studio.projectformat.format.RemoteParsedSpec

/**
 * Maps `moq-format`'s parse-result wire shape onto this module's own [ParsedSpec]. Lives here
 * (not in `studio-project-format`, where the wire types are defined) because `RemoteParsedSpec`
 * exists specifically to avoid a circular module dependency — `studio-project-format` can't
 * depend back on `studio-domain` for the real [ParsedSpec] type, so the wire shape stays a plain
 * DTO there and gets mapped onto the domain type here, where both are visible.
 */
fun RemoteParsedSpec.toParsedSpec(): ParsedSpec =
    ParsedSpec(
        title = title,
        version = version,
        endpoints = endpoints.map { it.toParsedEndpoint() },
        warnings = warnings,
    )

private fun RemoteParsedEndpoint.toParsedEndpoint(): ParsedEndpoint =
    ParsedEndpoint(
        method = method,
        path = path,
        alias = alias,
        description = description,
        referenceName = referenceName,
        tags = tags,
        responses = responses.map { it.toParsedResponse() },
        authType = authType,
        authHeaderName = authHeaderName,
        queryParameters = queryParameters,
        requiredQueryParameters = requiredQueryParameters,
        requiredHeaders = requiredHeaders,
        cookies = cookies,
        requiresBody = requiresBody,
        acceptedContentTypes = acceptedContentTypes,
    )

private fun RemoteParsedResponse.toParsedResponse(): ParsedResponse =
    ParsedResponse(
        name = name,
        statusCode = statusCode,
        headers = headers,
        body = body,
        bodyIsBase64 = isBase64,
        description = description,
    )
