package com.moqserver.studio

import com.moqserver.studio.endpointdetail.deleteAllCookies
import com.moqserver.studio.endpointdetail.deleteAllHeaders
import com.moqserver.studio.endpointdetail.hasHeaderEntries
import com.moqserver.studio.endpointdetail.updateHeadersState
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteAllBehaviorTest {

    @Test
    fun `header delete all is available for imported header rules without response headers`() {
        val requestRules = RequestRules(
            headers = listOf(
                RuleMatcher(
                    name = "Authorization",
                    required = true,
                    matchType = MatchType.EQUAL_TO,
                ),
            ),
        )

        assertTrue(hasHeaderEntries(emptyMap(), requestRules))
    }

    @Test
    fun `header delete all removes imported header rules`() {
        val requestRules = RequestRules(
            headers = listOf(
                RuleMatcher(name = "Authorization", required = true),
            ),
        )

        val updatedRules = deleteAllHeaders(requestRules)

        assertNull(updatedRules.headers)
        assertFalse(hasHeaderEntries(emptyMap(), updatedRules))
    }

    @Test
    fun `header delete all clears variant headers and request rules together`() {
        val endpoint = EndpointDocument(
            id = "get-users",
            method = "GET",
            path = "/users",
            requestRules = RequestRules(
                headers = listOf(RuleMatcher(name = "Authorization", required = true)),
            ),
            variants = listOf(
                ProjectVariant(
                    name = "Success",
                    status = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                ),
            ),
        )

        val updated = endpoint.updateHeadersState(
            index = 0,
            variant = endpoint.variants.single(),
            headers = emptyMap(),
            requestRules = deleteAllHeaders(endpoint.requestRules!!),
        )

        assertNull(updated.variants.single().headers)
        assertNull(updated.requestRules)
    }

    @Test
    fun `cookie delete all removes all cookie rules`() {
        val requestRules = RequestRules(
            cookies = listOf(
                RuleMatcher(name = "session_id", required = true),
            ),
        )

        val updatedRules = deleteAllCookies(requestRules)

        assertNull(updatedRules.cookies)
    }
}
