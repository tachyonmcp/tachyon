/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.temporal;

import io.temporal.activity.ActivityInterface;
import java.util.Map;

/** Reads the calendar that owns appointment slots. */
@ActivityInterface
public interface SlotAvailability {

    /**
     * Tells whether the requested slot can still be booked.
     *
     * @param booking the booking arguments
     * @return {@code true} if the slot is free right now
     */
    boolean isAvailable(Map<String, Object> booking);
}
