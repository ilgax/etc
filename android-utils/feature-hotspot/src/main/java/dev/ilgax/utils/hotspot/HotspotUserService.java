package dev.ilgax.utils.hotspot;

/** Shizuku UserService entry point; runs as the shell uid in its own process. */
public class HotspotUserService extends IHotspotService.Stub {
    private final HotspotEngine engine = new HotspotEngine();

    /** Shizuku instantiates this through the no-arg constructor. */
    public HotspotUserService() {
    }

    @Override
    public HotspotStatus start() {
        return engine.start();
    }

    @Override
    public HotspotStatus stop() {
        return engine.stop();
    }

    @Override
    public HotspotStatus status(boolean includePassword) {
        return engine.status(includePassword);
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
