package cloud.xuantong.security.model;
public enum ControlPlaneRole {
    SYSTEM_ADMIN, NAMESPACE_ADMIN, DEVELOPER, VIEWER;
    public boolean canWrite() { return this != VIEWER; }
    public boolean managesNamespace() { return this == SYSTEM_ADMIN || this == NAMESPACE_ADMIN; }
}
