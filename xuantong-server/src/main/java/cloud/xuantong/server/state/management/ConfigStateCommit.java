package cloud.xuantong.server.state.management;

record ConfigStateCommit(
        long contentRevision,
        long decisionRevision,
        long eventRevision) {

    ConfigStateCommit {
        if (contentRevision < 0 || decisionRevision < 1 || eventRevision < 1) {
            throw new IllegalArgumentException("Invalid Config State commit revisions");
        }
    }
}
