package com.moqserver.studio

import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AIActionHandlerTest {

	@Test
	fun `mergeGeneratedBodyIfNeeded appends generated items to existing top-level array for add prompt`() {
		val existing = YamlValue.Array(
			listOf(
				YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-1"))),
				YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-2"))),
			),
		)
		val generated = YamlValue.Array(
			listOf(
				YamlValue.Obj(mapOf("id" to YamlValue.Str("new-1"))),
				YamlValue.Obj(mapOf("id" to YamlValue.Str("new-2"))),
			),
		)

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "Generate up to 10 mocks and add them")

		val mergedArray = assertIs<YamlValue.Array>(merged)
		assertEquals(4, mergedArray.value.size)
		assertEquals(
			"existing-1",
			(mergedArray.value[0] as YamlValue.Obj).value.getValue("id").let { it as YamlValue.Str }.value,
		)
		assertEquals(
			"new-2",
			(mergedArray.value[3] as YamlValue.Obj).value.getValue("id").let { it as YamlValue.Str }.value,
		)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded avoids duplicating array items when generated body already includes existing items`() {
		val existing = YamlValue.Array(
			listOf(
				YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-1"))),
				YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-2"))),
			),
		)
		val generated = YamlValue.Array(
			listOf(
				YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-1"))),
				YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-2"))),
				YamlValue.Obj(mapOf("id" to YamlValue.Str("new-1"))),
			),
		)

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "add one more item")

		assertEquals(generated, merged)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded appends generated items to shared array field`() {
		val existing = YamlValue.Obj(
			mapOf(
				"videos" to YamlValue.Array(
					listOf(
						YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-1"))),
					),
				),
				"nextCursor" to YamlValue.Str("abc"),
			),
		)
		val generated = YamlValue.Obj(
			mapOf(
				"videos" to YamlValue.Array(
					listOf(
						YamlValue.Obj(mapOf("id" to YamlValue.Str("new-1"))),
						YamlValue.Obj(mapOf("id" to YamlValue.Str("new-2"))),
					),
				),
			),
		)

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "add 2 more videos")

		val mergedObject = assertIs<YamlValue.Obj>(merged)
		val videos = assertIs<YamlValue.Array>(mergedObject.value.getValue("videos"))
		assertEquals(3, videos.value.size)
		assertEquals("abc", (mergedObject.value.getValue("nextCursor") as YamlValue.Str).value)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded avoids duplicating wrapped array items when generated object already includes existing items`() {
		val existing = YamlValue.Obj(
			mapOf(
				"videos" to YamlValue.Array(
					listOf(
						YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-1"))),
					),
				),
				"nextCursor" to YamlValue.Str("abc"),
			),
		)
		val generated = YamlValue.Obj(
			mapOf(
				"videos" to YamlValue.Array(
					listOf(
						YamlValue.Obj(mapOf("id" to YamlValue.Str("existing-1"))),
						YamlValue.Obj(mapOf("id" to YamlValue.Str("new-1"))),
					),
				),
			),
		)

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "include one more video")

		val mergedObject = assertIs<YamlValue.Obj>(merged)
		val videos = assertIs<YamlValue.Array>(mergedObject.value.getValue("videos"))
		assertEquals(2, videos.value.size)
		assertEquals("abc", (mergedObject.value.getValue("nextCursor") as YamlValue.Str).value)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded does not merge for replacement prompt`() {
		val existing = YamlValue.Array(listOf(YamlValue.Int(1), YamlValue.Int(2)))
		val generated = YamlValue.Array(listOf(YamlValue.Int(9)))

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "replace the list with one item")

		assertEquals(generated, merged)
	}
}
