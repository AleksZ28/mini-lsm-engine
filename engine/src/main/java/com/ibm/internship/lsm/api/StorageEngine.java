package com.ibm.internship.lsm.api;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

public interface StorageEngine extends Closeable {
    void put(String key, byte[] value) throws IOException;
    ByteBuffer get(String key) throws IOException;
    void delete(String key) throws IOException;
    void flush() throws IOException;
    void recoverFromWal() throws IOException;

    @Override
    void close() throws IOException;
}