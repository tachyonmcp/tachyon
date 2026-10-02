package dev.tachyonmcp.docs.extensions.customextensions;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class SchedulerExtension implements ServerExtension {

    @Override
    public String extensionId() {
        return "com.example/scheduler";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.NEVER;
    }

    // snips-start: custom_ext_shutdown
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void shutdown() {
        scheduler.shutdown();
    }
    // snips-end: custom_ext_shutdown

    boolean schedulerStopped() {
        return scheduler.isShutdown();
    }
}
