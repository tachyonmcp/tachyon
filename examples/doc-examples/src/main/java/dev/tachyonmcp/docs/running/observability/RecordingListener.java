package dev.tachyonmcp.docs.running.observability;

import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;

public final class RecordingListener implements ObservationListener {

    @Override
    public ObservationScope start(OperationInfo info) {
        System.out.println("OBS start " + info.method());
        return ObservationScope.NOOP;
    }

    @Override
    public void complete(OperationInfo info, OperationOutcome outcome) {
        System.out.println("OBS complete " + info.method() + " " + outcome.getClass().getSimpleName());
    }
}
