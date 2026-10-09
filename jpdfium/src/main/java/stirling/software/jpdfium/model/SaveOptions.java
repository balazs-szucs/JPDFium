package stirling.software.jpdfium.model;

/**
 * Output policy for document saves.
 *
 * <p>Memory/latency trade-offs are explicit: {@code saveBytes()} materializes the whole document (high peak); {@code saveTo(Path)} streams to a sibling temp file then atomically publishes (bounded memory); {@code saveTo(channel)} spools to a temp file under the guard, then transfers in bounded chunks with the guard released.
 */
public final class SaveOptions {

    private final long maxOutputBytes;
    private final boolean verifyReopen;

    private SaveOptions(Builder b) {
        this.maxOutputBytes = b.maxOutputBytes;
        this.verifyReopen = b.verifyReopen;
    }

    /** Maximum accepted output size in bytes; {@code 0} disables the bound. */
    public long maxOutputBytes() {
        return maxOutputBytes;
    }

    /** Whether the staged file must reopen before it is published. */
    public boolean verifyReopen() {
        return verifyReopen;
    }

    /** Low-overhead file save without reopen validation. */
    public static SaveOptions fast() {
        return builder().build();
    }

    /** Save with a reopen validation pass before publish. */
    public static SaveOptions validated() {
        return builder().verifyReopen(true).build();
    }

    /** Save refusing outputs larger than {@code maxBytes}. */
    public static SaveOptions maxOutputBytes(long maxBytes) {
        return builder().maxOutputBytes(maxBytes).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().maxOutputBytes(maxOutputBytes).verifyReopen(verifyReopen);
    }

    public static final class Builder {
        private long maxOutputBytes;
        private boolean verifyReopen;

        private Builder() {}

        /**
         * Refuse outputs larger than {@code maxBytes}.
         * Native streaming saves abort mid-write; buffered/channel paths fail during staging before publish.
         */
        public Builder maxOutputBytes(long maxBytes) {
            if (maxBytes < 0) throw new IllegalArgumentException("maxOutputBytes must be >= 0");
            this.maxOutputBytes = maxBytes;
            return this;
        }

        /** Reopen the staged file before publish (extra I/O, stronger guarantee). */
        public Builder verifyReopen(boolean verify) {
            this.verifyReopen = verify;
            return this;
        }

        public SaveOptions build() {
            return new SaveOptions(this);
        }
    }
}
