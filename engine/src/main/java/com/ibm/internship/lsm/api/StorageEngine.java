package com.ibm.internship.lsm.api;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

public interface StorageEngine extends Closeable {
    void put(String key, byte[] value) throws IOException;
    Optional<byte[]> get(String key) throws IOException;
    void delete(String key) throws IOException;
    void flush() throws IOException;
    void recoverFromWal() throws IOException;

    @Override
    void close() throws IOException;
}