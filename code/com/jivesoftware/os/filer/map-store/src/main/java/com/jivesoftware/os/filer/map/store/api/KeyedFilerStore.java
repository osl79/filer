package com.jivesoftware.os.filer.map.store.api;

import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerTransaction;
import com.jivesoftware.os.filer.io.IBA;
import com.jivesoftware.os.filer.io.RewriteFilerTransaction;
import java.io.IOException;
import java.util.List;

/**
 *
 */
public interface KeyedFilerStore {

    <R> R execute(byte[] keyBytes, long newFilerInitialCapacity, FilerTransaction<Filer, R> transaction) throws IOException;

    <R> R executeRewrite(byte[] keyBytes, long newFilerInitialCapacity, RewriteFilerTransaction<Filer, R> transaction) throws IOException;

    boolean stream(List<KeyRange> ranges, KeyValueStore.EntryStream<IBA, Filer> stream) throws IOException;

    /**
     *
     * @param ranges nullable null means all keys.
     * @param stream
     * @return
     * @throws IOException
     */
    boolean streamKeys(List<KeyRange> ranges, KeyValueStore.KeyStream<IBA> stream) throws IOException;

    void close();

}