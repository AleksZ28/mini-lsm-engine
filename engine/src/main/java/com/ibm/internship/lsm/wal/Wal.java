package com.ibm.internship.lsm.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class Wal implements AutoCloseable {
    private final FileChannel channel;
    private final Path logPath;

    public Wal(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.channel.position(this.channel.size());
        this.logPath = path;
    }


    public void append(String key, String value, boolean isDelete) throws IOException {
        ByteBuffer buffer = WalEncoder.encode(key, value, isDelete);

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    public void reset() throws IOException {
        channel.truncate(0);
        channel.position(0);
    }

    public long size()  throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
    }
}
