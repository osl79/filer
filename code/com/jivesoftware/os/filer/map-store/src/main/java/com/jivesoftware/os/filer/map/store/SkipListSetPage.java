package com.jivesoftware.os.filer.map.store;

import com.jivesoftware.os.filer.io.ByteArrayFiler;
import com.jivesoftware.os.filer.io.ConcurrentFiler;
import java.io.IOException;

/**
 *
 * @author jonathan
 */
public class SkipListSetPage<F extends ConcurrentFiler> {

    /**
     *
     */
    final MapChunk<F> map;
    byte maxHeight;
    SkipListComparator valueComparator;
    long headIndex;
    byte[] headKey;

    /**
     *
     * @param page
     * @param headKey
     * @param _valueComparator
     */
    public SkipListSetPage(MapChunk<F> page, byte[] headKey, SkipListComparator _valueComparator) {
        this.map = page;
        this.headKey = headKey;
        valueComparator = _valueComparator;
    }

    /**
     *
     * @param mapStore
     */
    public void init(MapStore mapStore) throws IOException {
        map.init(mapStore);
        maxHeight = heightFit(map.capacity);
        headIndex = mapStore.get(map, headKey);
        if (headIndex == -1) {
            throw new RuntimeException("SkipListSetPage:Invalid Page!");
        }
    }

    /**
     *
     * @param bytes
     * @return
     */
    public boolean isHeadKey(byte[] bytes) {
        if (bytes.length != headKey.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != headKey[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     *
     * @param startOfAKey
     * @param startOfBKey
     * @return
     */
    public int compare(int startOfAKey, int startOfBKey) throws IOException {
        return valueComparator.compare(map, startOfAKey, map, startOfBKey, map.keySize);
    }

    /**
     *
     * @param startOfAKey
     * @param _key
     * @return
     */
    public int compare(int startOfAKey, byte[] _key) throws IOException {
        return valueComparator.compare(map, startOfAKey, new MapChunk(new ByteArrayFiler(_key)), 0, map.keySize);
    }

    /**
     *
     * @param _length
     * @return
     */
    public static byte heightFit(long _length) {
        byte h = (byte) 1; // room for back links
        for (byte i = 1; i < 65; i++) { // 2^64 == long so why go anyfuther
            if (_length < Math.pow(2, i)) {
                return (byte) (h + i);
            }
        }
        return 64;
    }
}
