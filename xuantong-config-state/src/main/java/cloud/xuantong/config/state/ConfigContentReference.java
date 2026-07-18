package cloud.xuantong.config.state;

/** Reference resolved atomically while applying a configuration decision. */
public sealed interface ConfigContentReference
        permits ConfigContentReference.Existing, ConfigContentReference.NewContent {

    static Existing existing(long contentRevision) {
        return new Existing(contentRevision);
    }

    static NewContent newContent() {
        return NewContent.INSTANCE;
    }

    record Existing(long contentRevision) implements ConfigContentReference {
        public Existing {
            if (contentRevision < 1) {
                throw new IllegalArgumentException("contentRevision must be positive");
            }
        }
    }

    final class NewContent implements ConfigContentReference {
        private static final NewContent INSTANCE = new NewContent();

        private NewContent() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NewContent;
        }

        @Override
        public int hashCode() {
            return NewContent.class.hashCode();
        }

        @Override
        public String toString() {
            return "NEW_CONTENT";
        }
    }
}
