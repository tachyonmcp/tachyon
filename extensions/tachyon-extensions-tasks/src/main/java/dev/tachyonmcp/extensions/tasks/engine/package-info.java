/*
 * Copyright (c) 2026 Konstantin Pavlov and contributors.
 */

/**
 * Protocol-neutral task engine: revision-ordered projection cache, retention, and push events that
 * protocol bindings (MCP today) encode. Must not depend on any protocol; see {@code EngineBoundaryTest}.
 */
@NullMarked
@InternalApi
package dev.tachyonmcp.extensions.tasks.engine;

import dev.tachyonmcp.api.annotations.InternalApi;
import org.jspecify.annotations.NullMarked;
