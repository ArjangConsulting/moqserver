package com.moqserver.studio.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Creates an SLF4J [Logger] bound to the reified type [T]. */
inline fun <reified T> loggerFor(): Logger = LoggerFactory.getLogger(T::class.java)
