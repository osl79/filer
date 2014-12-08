package com.jivesoftware.os.filer.chunk.store;

import com.google.common.base.Preconditions;
import com.jivesoftware.os.filer.io.ByteArrayStripingLocksProvider;
import com.jivesoftware.os.filer.io.ConcurrentFilerFactory;
import com.jivesoftware.os.filer.io.ConcurrentFilerProvider;
import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.map.store.MapChunk;
import com.jivesoftware.os.filer.map.store.MapStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author jonathan.colt
 */
public class MultiChunkStoreConcurrentFilerFactory implements ConcurrentFilerFactory<ChunkFiler>, MultiChunkStore {

    static public class Builder {

        private final ArrayList<ChunkStore> stores = new ArrayList<>();

        private int stripingLevel = 1024;

        public Builder setStripingLevel(int stripingLevel) {
            this.stripingLevel = stripingLevel;
            return this;
        }

        public Builder addChunkStore(ChunkStore chunkStore) {
            stores.add(chunkStore);
            return this;
        }

        public MultiChunkStoreConcurrentFilerFactory build() throws IOException {
            return new MultiChunkStoreConcurrentFilerFactory(stripingLevel, stores.toArray(new ChunkStore[stores.size()]));
        }
    }

    private static final long MAGIC_SKY_HOOK_NUMBER = 5583112375L;
    private final long skyHookFP = 464; // I died a little bit doing this.

    final ChunkStore[] chunkStores;
    private final int maxKeySizePower = 16;
    private final AtomicReference<MapChunk<ChunkFiler>>[][] chunkIndexes;
    private final ByteArrayStripingLocksProvider[] locksProviders;

    private MultiChunkStoreConcurrentFilerFactory(int stripingLevel, ChunkStore... chunkStores) throws IOException {
        this.chunkStores = chunkStores;
        this.locksProviders = new ByteArrayStripingLocksProvider[chunkStores.length];
        this.chunkIndexes = new AtomicReference[chunkStores.length][maxKeySizePower];
        for (int i = 0; i < chunkStores.length; i++) {
            locksProviders[i] = new ByteArrayStripingLocksProvider(stripingLevel);
            for (int keyPower = 0; keyPower < maxKeySizePower; keyPower++) {
                chunkIndexes[i][keyPower] = new AtomicReference<>();
            }
        }
        for (ChunkStore chunkStore : chunkStores) {
            initializeIfNeeded(chunkStore);
        }
    }

    private void initializeIfNeeded(final ChunkStore chunkStore) throws IOException {
        long magic;
        try {
            ChunkFiler filer = chunkStore.getFiler(skyHookFP, new Object());
            filer.seek(0);
            magic = FilerIO.readLong(filer, "magic");
        } catch (IOException x) {
            magic = MAGIC_SKY_HOOK_NUMBER;
            long newSkyHookFP = chunkStore.newChunk(8 + (8 * maxKeySizePower));
            if (newSkyHookFP != skyHookFP) {
                throw new IOException("Its expected that the first ever allocated chunk will be at:" + skyHookFP + " but was at:" + newSkyHookFP);
            }
            try (ChunkFiler skyHookFiler = chunkStore.getFiler(newSkyHookFP, new Object())) {
                synchronized (skyHookFiler.lock()) {
                    skyHookFiler.seek(0);
                    FilerIO.writeLong(skyHookFiler, MAGIC_SKY_HOOK_NUMBER, "magic");
                    for (int i = 1, keySize = 1; i < maxKeySizePower; i++, keySize *= 2) {
                        ConcurrentFilerProvider<ChunkFiler> concurrentFilerProvider = new ConcurrentFilerProvider<>(null, new Magic(chunkStore));
                        ChunkFiler mapStoresFiler = MapStore.DEFAULT.allocateFiler(2, keySize, true, 8, false, concurrentFilerProvider);
                        MapStore.DEFAULT.bootstrapAllocatedFiler(2, keySize, true, 8, false, mapStoresFiler);
                        MapChunk<ChunkFiler> mapChunk = new MapChunk<>(mapStoresFiler);
                        mapChunk.init(MapStore.DEFAULT);
                        FilerIO.writeLong(skyHookFiler, mapStoresFiler.getChunkFP(), "");
                        //System.out.println("Wrote FP:" + mapStoresFiler.getChunkFP() + " for mapstore keySize:" + keySize);
                    }
                }
            }
        }
        if (magic != MAGIC_SKY_HOOK_NUMBER) {
            throw new IOException("Expected magic number:" + MAGIC_SKY_HOOK_NUMBER + " but found:" + magic);
        }
    }

    private class Magic implements ConcurrentFilerFactory<ChunkFiler> {

        private final ChunkStore chunkStore;

        public Magic(ChunkStore chunkStore) {
            this.chunkStore = chunkStore;
        }

        @Override
        public ChunkFiler get(byte[] key) throws IOException {
            throw new UnsupportedOperationException("Should not be called for Magic factory");
        }

        @Override
        public ChunkFiler allocate(byte[] key, long size) throws IOException {
            long newChunkFP = chunkStore.newChunk(size);
            return chunkStore.getFiler(newChunkFP, null);
        }

        @Override
        public ChunkFiler reallocate(byte[] key, ChunkFiler old, long newSize) throws IOException {
            throw new RuntimeException("This should never called.");
        }

    }

    private MapChunk growMapChunkIfNeeded(AtomicReference<MapChunk<ChunkFiler>> atomicMapChunk,
        int keyLength,
        ChunkStore chunkStore) throws IOException {

        MapChunk<ChunkFiler> mapChunk = atomicMapChunk.get();
        try {
            if (MapStore.DEFAULT.isFull(mapChunk)) {
                int newSize = MapStore.DEFAULT.nextGrowSize(mapChunk);
                int chunkPower = FilerIO.chunkPower(keyLength, 1);

                ConcurrentFilerProvider<ChunkFiler> concurrentFilerProvider = new ConcurrentFilerProvider<>(null, new Magic(chunkStore));
                ChunkFiler mapStoresFiler = MapStore.DEFAULT.allocateFiler(newSize, chunkPower, true, 8, false, concurrentFilerProvider);
                MapChunk<ChunkFiler> newMapChunk = MapStore.DEFAULT.bootstrapAllocatedFiler(newSize, chunkPower, true, 8, false, mapStoresFiler);
                MapStore.DEFAULT.copyTo(mapChunk, newMapChunk, null);

                long oldFP;
                try (ChunkFiler skyHookFiler = chunkStore.getFiler(skyHookFP, new Object())) {
                    synchronized (skyHookFiler.lock()) {
                        skyHookFiler.seek(8 + (8 * chunkPower));
                        oldFP = FilerIO.readLong(skyHookFiler, "");
                        skyHookFiler.seek(8 + (8 * chunkPower));
                        FilerIO.writeLong(skyHookFiler, mapStoresFiler.getChunkFP(), "");
                        atomicMapChunk.set(newMapChunk);
                    }
                }
                chunkStore.recycle(chunkStore.getFiler(oldFP, null));
                return newMapChunk;
            }
            return mapChunk;
        } catch (Exception e) {
            throw new IOException("Error when expanding size of partition!", e);
        }
    }

    private MapChunk getMapChunkIndex(AtomicReference<MapChunk<ChunkFiler>> chunkIndex, ChunkStore chunkStore, int keyLength) throws IOException {

        MapChunk<ChunkFiler> mapChunk = chunkIndex.get();
        if (mapChunk == null) {
            Object lock = new Object();
            long fpIndexFP;
            try (ChunkFiler chunkFiler = chunkStore.getFiler(skyHookFP, lock)) {
                synchronized (chunkFiler.lock()) {
                    chunkFiler.seek(8 + (FilerIO.chunkPower(keyLength, 0) * 8));
                    fpIndexFP = FilerIO.readLong(chunkFiler, "mapIndexFP");
                }
            }
            ChunkFiler chunkIndexFiler = chunkStore.getFiler(fpIndexFP, lock);
            mapChunk = new MapChunk<>(chunkIndexFiler);
            mapChunk.init(MapStore.DEFAULT);
            if (!chunkIndex.compareAndSet(null, mapChunk)) {
                mapChunk = chunkIndex.get();
            }
        }
        return mapChunk;
    }

    @Override
    public ChunkFiler get(byte[] key) throws IOException {
        return getOrAllocate(key, -1);
    }

    @Override
    public ChunkFiler allocate(byte[] key, long size) throws IOException {
        Preconditions.checkArgument(size > 0, "Size must be positive");
        return getOrAllocate(key, size);
    }

    private ChunkFiler getOrAllocate(byte[] key, long size) throws IOException {
        int i = getChunkIndexForKey(key);
        AtomicReference<MapChunk<ChunkFiler>> chunkIndex = chunkIndexes[i][FilerIO.chunkPower(key.length, 0)];
        MapChunk mapChunkIndex = getMapChunkIndex(chunkIndex, chunkStores[i], key.length);
        long chunkFP = -1;
        long ai = MapStore.DEFAULT.get(mapChunkIndex, key);
        if (ai >= 0) {
            chunkFP = FilerIO.bytesLong(MapStore.DEFAULT.getPayload(mapChunkIndex, ai));
        }
        if (chunkFP == -1 && size > 0) {
            chunkFP = chunkStores[i].newChunk(size);
            MapChunk mapChunk = growMapChunkIfNeeded(chunkIndex, key.length, chunkStores[i]);
            MapStore.DEFAULT.add(mapChunk, (byte) 1, key, FilerIO.longBytes(chunkFP));
        }
        if (chunkFP >= 0) {
            return chunkStores[i].getFiler(chunkFP, locksProviders[i].lock(key));
        }
        return null;
    }

    @Override
    public ChunkFiler reallocate(byte[] key, ChunkFiler oldFiler, long newSize) throws IOException {
        ChunkFiler newFiler = allocate(key, newSize);
        if (oldFiler == null) {
            return newFiler;
        }
        FilerIO.copy(oldFiler, newFiler, -1);
        oldFiler.recycle();
        return newFiler;
    }

    @Override
    public void allChunks(ChunkIdStream _chunks) throws Exception {
        for (ChunkStore chunkStore : chunkStores) {
            chunkStore.allChunks(_chunks);
        }
    }

    private int getChunkIndexForKey(byte[] key) {
        return Math.abs(Arrays.hashCode(key)) % chunkStores.length;
    }

    @Override
    public long newChunk(byte[] key, long _capacity) throws Exception {
        return chunkStores[getChunkIndexForKey(key)].newChunk(_capacity);
    }

    @Override
    public Filer getFiler(byte[] key, long _chunkFP) throws Exception {
        int i = getChunkIndexForKey(key);
        Object lock = locksProviders[i].lock(key);
        return chunkStores[i].getFiler(_chunkFP, lock);
    }

    @Override
    public void remove(byte[] key, long _chunkFP) throws Exception {
        chunkStores[getChunkIndexForKey(key)].remove(_chunkFP);
    }

    @Override
    public void delete() throws Exception {
        for (ChunkStore chunkStore : chunkStores) {
            chunkStore.delete();
        }
    }
}
