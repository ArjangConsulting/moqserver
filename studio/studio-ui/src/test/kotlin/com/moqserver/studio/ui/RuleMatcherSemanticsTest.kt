package com.moqserver.studio.ui

import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RuleMatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/** The editor must show the comparison Swift's `RuleEvaluator` applies, not a guess. */
class RuleMatcherSemanticsTest {
	@Test
	fun `a rule without match_type resolves the way the server resolves it`() {
		assertEquals(MatchType.REQUIRE, RuleMatcher(name = "a", required = true).effectiveMatchType)
		assertEquals(MatchType.EQUAL_TO, RuleMatcher(name = "a", required = true, match = "x").effectiveMatchType)
		assertEquals(MatchType.EQUAL_TO, RuleMatcher(name = "a").effectiveMatchType)
		assertEquals(MatchType.GTE, RuleMatcher(name = "a", matchType = MatchType.GTE).effectiveMatchType)
	}

	@Test
	fun `unticking Required writes required false and keeps the comparison`() {
		val rule = RuleMatcher(name = "sid", match = "abc", required = true, matchType = MatchType.EQUAL_TO)

		assertEquals(rule.copy(required = false), rule.withRequired(false))
	}

	@Test
	fun `ticking Required on a legacy rule pins the comparison it already had`() {
		val legacy = RuleMatcher(name = "sid", match = "abc")

		assertEquals(
			RuleMatcher(name = "sid", match = "abc", required = true, matchType = MatchType.EQUAL_TO),
			legacy.withRequired(true),
		)
	}

	@Test
	fun `a new rule requires presence rather than an empty equality check`() {
		assertEquals(RuleMatcher(name = "", required = true, matchType = MatchType.REQUIRE), newRequiredRule())
	}
}
