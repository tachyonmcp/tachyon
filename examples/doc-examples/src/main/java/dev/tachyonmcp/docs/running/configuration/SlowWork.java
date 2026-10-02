package dev.tachyonmcp.docs.running.configuration;

final class SlowWork {

    static final int total = 4;

    private SlowWork() {}

    static void doSlowStep(int step) throws InterruptedException {
        Thread.sleep(20);
    }
}
