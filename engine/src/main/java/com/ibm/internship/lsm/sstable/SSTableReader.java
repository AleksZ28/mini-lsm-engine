package com.ibm.internship.lsm.sstable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class SSTableReader implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final SSTableFooter footer;

    public SSTableReader(Path path) throws IOException {
        this.path = Objects.requireNonNull(path, "path cannot be null");
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            this.footer = SSTableFooter.read(this.channel);
        } catch (Throwable t) {
            try {
                this.channel.close();
            } catch (IOException closeEx) {
                t.addSuppressed(closeEx);
            }
            throw t;
        }
    }

    public static SSTableReader open(Path path) throws IOException {
        return new SSTableReader(path);
    }

    public SSTableFooter getFooter() {
        return footer;
    }

    public Path getPath() {
        return path;
    }

    public long getFileSize() throws IOException {
        return channel.size();
    }

    FileChannel getChannel() {
        return channel;
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }
}
