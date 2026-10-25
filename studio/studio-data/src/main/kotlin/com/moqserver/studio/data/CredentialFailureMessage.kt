package com.moqserver.studio.data

import com.maniramezan.credentialkeychain.KeychainUnavailableException

internal fun credentialFailureMessage(reason: KeychainUnavailableException.Reason): String = when (reason) {
	KeychainUnavailableException.Reason.Unsupported ->
		"API keys cannot be saved because secure credential storage is unavailable. Enable your operating system's secure storage and retry."
	KeychainUnavailableException.Reason.Locked ->
		"Your credential store is locked or access was denied. Unlock it or allow access, then retry."
	KeychainUnavailableException.Reason.Canceled ->
		"Credential access was canceled. Retry and allow access to your saved API keys."
	KeychainUnavailableException.Reason.AuthenticationInvalidated ->
		"Authentication for your saved API keys is no longer valid. Restore access to your credential store, then re-enter the affected keys."
	KeychainUnavailableException.Reason.Corrupted ->
		"Your credential store could not read the saved API keys. Repair the affected entries in your system credential manager, then retry."
	KeychainUnavailableException.Reason.Failed ->
		"Your credential store could not complete the operation. Check your system credential manager and retry."
}
