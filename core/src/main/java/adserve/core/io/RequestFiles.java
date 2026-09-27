package adserve.core.io;

import ads.v1.AdRequest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Ad requests stored as length-delimited protobuf, one per ad break, in arrival order. */
public final class RequestFiles {
    private RequestFiles() {}

    public static final class Writer implements AutoCloseable {
        private final OutputStream out;
        private long count;

        public Writer(Path p) throws IOException {
            out = new BufferedOutputStream(Files.newOutputStream(p), 1 << 20);
        }

        public void write(AdRequest r) {
            try {
                r.writeDelimitedTo(out);
                count++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public long count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    public static long forEach(Path p, Consumer<AdRequest> f) throws IOException {
        long n = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
            AdRequest r;
            while ((r = AdRequest.parseDelimitedFrom(in)) != null) {
                f.accept(r);
                n++;
            }
        }
        return n;
    }

    public static List<AdRequest> readAll(Path p, int limit) throws IOException {
        List<AdRequest> out = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
            AdRequest r;
            while (out.size() < limit && (r = AdRequest.parseDelimitedFrom(in)) != null) out.add(r);
        }
        return out;
    }
}
