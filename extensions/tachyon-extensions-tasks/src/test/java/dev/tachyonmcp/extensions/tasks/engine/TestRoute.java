/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

/** A binding-agnostic route for engine tests. */
record TestRoute(String owner) implements TaskRoute {}
