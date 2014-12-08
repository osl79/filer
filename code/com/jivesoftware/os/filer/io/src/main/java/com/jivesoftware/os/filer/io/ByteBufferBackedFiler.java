/*
 * $Revision$
 * $Date$
 *
 * Copyright (C) 1999-$year$ Jive Software. All rights reserved.
 *
 * This software is the proprietary information of Jive Software. Use is subject to license terms.
 */
package com.jivesoftware.os.filer.io;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 *
 * @author jonathan.colt
 */
public class ByteBufferBackedFiler implements ConcurrentFiler {

    private final Object lock;
    private final ByteBuffer buffer;

    public ByteBufferBackedFiler(Object lock, ByteBuffer buffer) {
        this.lock = lock;
        this.buffer = buffer;
    }

    @Override
    public Object lock() {
        return lock;
    }

    @Override
    public void seek(long position) throws IOException {
        buffer.position((int) position); // what a pain! limited to an int!
    }

    @Override
    public long skip(long position) throws IOException {
        int p = buffer.position();
        p += position;
        buffer.position(p);
        return p;
    }

    @Override
    public long length() throws IOException {
        return buffer.capacity();
    }

    @Override
    public void setLength(long len) throws IOException {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public long getFilePointer() throws IOException {
        return buffer.position();
    }

    @Override
    public void eof() throws IOException {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public int read() throws IOException {
        int remaining = buffer.remaining();
        if (remaining == 0) {
            return -1;
        }
        return buffer.get();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int _offset, int _len) throws IOException {
        int remaining = buffer.remaining();
        if (remaining == 0) {
            return -1;
        }
        int count = Math.min(_len, remaining);
        buffer.get(b, _offset, count);
        return count;
    }


    @Override
    public void close() throws IOException {

    }

    @Override
    public void write(int b) throws IOException {
        buffer.put((byte) b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        buffer.put(b);
    }

    @Override
    public void write(byte[] b, int _offset, int _len) throws IOException {
        buffer.put(b, _offset, _len);
    }

    @Override
    public ConcurrentFiler asConcurrentReadWrite(Object suggestedLock) throws IOException {
        return new ByteBufferBackedFiler(suggestedLock, buffer.duplicate());
    }

    @Override
    public void delete() {
        DirectBufferCleaner.clean(buffer);
    }

    @Override
    public long capacity() {
        return buffer.capacity();
    }

}
