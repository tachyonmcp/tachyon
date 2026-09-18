/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.features.resources.ResourceFn;

/** Resource function whose response must not be shared between clients by protocol caches. */
@InternalApi
@FunctionalInterface
public interface PrivateCachingResourceFn extends ResourceFn {}
