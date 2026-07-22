package cloud.xuantong.server.cluster;

public interface GatewayClusterViewProvider {
    GatewayClusterView currentView();

    default GatewayClusterSummary currentSummary() {
        return GatewayClusterSummary.from(currentView());
    }
}
