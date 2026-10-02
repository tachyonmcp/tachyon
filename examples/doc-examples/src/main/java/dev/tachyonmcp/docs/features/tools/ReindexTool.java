package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

final class ReindexTool {

    static final int BATCH_COUNT = 40;
    static final AtomicInteger REINDEXED = new AtomicInteger();

    private ReindexTool() {}

    private static List<Integer> batches() {
        return IntStream.range(0, BATCH_COUNT).boxed().toList();
    }

    private static void reindex(int batch) {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        REINDEXED.incrementAndGet();
    }

    static void register(TachyonServer server) {
        // snips-start: tools_reindex
        server.tools().register(b -> b.name("reindex"), (context, request) -> {
            var undeliverable = context.responseUndeliverable().toCompletableFuture();
            for (var batch : batches()) {
                if (undeliverable.isDone()) {
                    return ToolResult.text("stopped: nobody is waiting");
                }
                reindex(batch);
            }
            return ToolResult.text("done");
        });
        // snips-end: tools_reindex
    }
}
