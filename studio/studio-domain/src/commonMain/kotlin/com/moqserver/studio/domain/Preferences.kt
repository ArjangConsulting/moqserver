package com.moqserver.studio.domain

import kotlinx.serialization.Serializable

@Serializable
enum class VariantReferenceSyncPreference {
	ALWAYS_UPDATE,
	NEVER_UPDATE,
}
