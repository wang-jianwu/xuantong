package cloud.xuantong.raft.ratis;

/** Public version constants for deployment gates and compatibility documentation. */
public final class RatisStateMessageVersions {
    public static final int MINIMUM_ENVELOPE_VERSION =
            RatisStateMessageCodec.MINIMUM_VERSION;
    public static final int CURRENT_ENVELOPE_VERSION =
            RatisStateMessageCodec.CURRENT_VERSION;

    private RatisStateMessageVersions() {
    }
}
