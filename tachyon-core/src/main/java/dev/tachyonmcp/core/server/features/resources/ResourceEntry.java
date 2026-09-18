/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.features.resources.AsyncResourceFn;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;

@InternalApi
record ResourceEntry(ResourceDescriptor descriptor, AsyncResourceFn fn, boolean privateCaching) {}
