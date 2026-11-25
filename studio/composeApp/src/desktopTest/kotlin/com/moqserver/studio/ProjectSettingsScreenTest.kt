package com.moqserver.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.GlobalRules
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectScenario
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectsettings.ADDONS_MUST_BE_OBJECT
import com.moqserver.studio.projectsettings.ProjectSettingsScreen
import io.github.maniramezan.testcommons.compose.ThemedComposeRule
import io.github.maniramezan.testcommons.compose.captureToPng
import io.github.maniramezan.testcommons.compose.replaceTextVerified
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectSettingsScreenTest {
	@get:Rule
	val compose = ThemedComposeRule(theme = { content ->
		StudioTheme(themeMode = StudioThemeMode.LIGHT) { Surface(Modifier.fillMaxSize(), content = content) }
	})

	private val endpoints = listOf(
		EndpointDocument(
			id = "get-profile",
			alias = "Profile",
			method = "GET",
			path = "/profile",
			variants = listOf(
				ProjectVariant(name = "ok", status = 200, isDefault = true),
				ProjectVariant(name = "unauthorized", status = 401),
			),
		),
		EndpointDocument(
			id = "get-orders",
			alias = "Orders",
			method = "GET",
			path = "/orders",
			variants = listOf(ProjectVariant(name = "ok", status = 200)),
		),
	)

	private var manifest by mutableStateOf(
		ProjectManifest(
			name = "Shop",
			defaults = ProjectDefaults(
				auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
				network = NetworkBehavior(),
			),
		),
	)

	private fun show() {
		compose.setContent {
			ProjectSettingsScreen(manifest = manifest, endpoints = endpoints, onUpdateManifest = { manifest = it })
		}
	}

	@Test
	fun `scenarios are created, renamed, extended and guarded against duplicate names`() {
		show()
		compose.onNodeWithText("Add scenario").performScrollTo().performClick()
		compose.runOnIdle {
			assertEquals(mapOf("scenario-1" to ProjectScenario(variants = mapOf("get-profile" to "ok"))), manifest.scenarios)
		}
		field("Scenario name").performScrollTo().replaceTextVerified("logged-out")
		compose.onNodeWithText("Add endpoint").performScrollTo().performClick()
		compose.runOnIdle {
			assertEquals(
				mapOf("logged-out" to ProjectScenario(variants = mapOf("get-profile" to "ok", "get-orders" to "ok"))),
				manifest.scenarios,
			)
		}

		compose.onNodeWithText("Add scenario").performScrollTo().performClick()
		val secondName = compose.onAllNodes(hasSetTextAction() and hasText("Scenario name"))[1]
		secondName.performScrollTo().replaceTextVerified("logged-out")
		compose.onNodeWithText("Another scenario already has this name").assertExists()
		compose.runOnIdle { assertEquals(listOf("logged-out", "scenario-1"), manifest.scenarios?.keys?.toList()) }
		captureEvidence("project-settings-scenarios")
	}

	@Test
	fun `add-ons are edited as JSON and invalid text leaves the manifest unchanged`() {
		show()
		val addonsJson = """{"jwt-claims": {"verify_signature": false}}"""
		field("Enabled add-ons (JSON)").performScrollTo().replaceTextVerified(addonsJson)
		val enabled = mapOf("jwt-claims" to YamlValue.Obj(mapOf("verify_signature" to YamlValue.Bool(false))))
		compose.runOnIdle { assertEquals(enabled, manifest.addons) }

		field("Enabled add-ons (JSON)").performScrollTo().replaceTextVerified("""["jwt-claims"]""")
		compose.onNodeWithText(ADDONS_MUST_BE_OBJECT).assertExists()
		compose.runOnIdle { assertEquals(enabled, manifest.addons) }

		field("Enabled add-ons (JSON)").performScrollTo().replaceTextVerified("{}")
		compose.runOnIdle { assertEquals(null, manifest.addons) }
	}

	@Test
	fun `project-wide header and cookie rules are written to global_rules`() {
		show()
		compose.onNode(hasText("Require at least one cookie") and isToggleable()).performScrollTo().performClick()
		compose.onAllNodesWithText("Add")[0].performScrollTo().performClick()
		field("Header Name").performScrollTo().replaceTextVerified("X-App")
		compose.runOnIdle {
			assertEquals(
				GlobalRules(
					requiredHeaders = listOf(RuleMatcher(name = "X-App", required = true, matchType = MatchType.REQUIRE)),
					verifyCookies = true,
				),
				manifest.globalRules,
			)
		}
		compose.onNodeWithText("Clear").performScrollTo().performClick()
		compose.onNode(hasText("Require at least one cookie") and isToggleable()).performScrollTo().performClick()
		compose.runOnIdle { assertEquals(null, manifest.globalRules) }
	}

	@Test
	fun `defaults edit delay and credential checking once an auth type is chosen`() {
		show()
		field("Delay (ms)").performScrollTo().replaceTextVerified("40")
		compose.onNode(hasText("Check credentials") and isToggleable()).performScrollTo().assertIsNotEnabled()
		compose.onNodeWithText("None").performScrollTo().performClick()
		compose.onNodeWithText("Bearer Token").performClick()
		compose.onNode(hasText("Check credentials") and isToggleable()).performScrollTo().performClick()
		compose.runOnIdle {
			assertEquals(40, manifest.defaults.delayMs)
			assertEquals(ProjectAuthConfig(type = AuthType.BEARER, verify = true), manifest.defaults.auth)
		}
		captureEvidence("project-settings-defaults")
	}

	private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

	private fun captureEvidence(name: String) {
		val destination = File("build/reports/ui-integration/$name.png")
		destination.parentFile.mkdirs()
		destination.writeBytes(compose.onAllNodes(isRoot())[0].captureToPng())
	}
}
