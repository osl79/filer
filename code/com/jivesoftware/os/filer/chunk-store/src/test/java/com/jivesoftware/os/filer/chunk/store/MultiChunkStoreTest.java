/*
 * Copyright 2014 Jive Software.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jivesoftware.os.filer.chunk.store;

import com.jivesoftware.os.filer.io.ByteArrayStripingLocksProvider;
import com.jivesoftware.os.filer.io.ByteBufferFactory;
import com.jivesoftware.os.filer.io.ConcurrentFilerProvider;
import com.jivesoftware.os.filer.io.FileBackedMemMappedByteBufferFactory;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.MonkeyFilerTransaction;
import com.jivesoftware.os.filer.io.HeapByteBufferFactory;
import com.jivesoftware.os.filer.io.KeyValueMarshaller;
import com.jivesoftware.os.filer.map.store.ConcurrentFilerProviderBackedMapChunkProvider;
import com.jivesoftware.os.filer.map.store.PartitionedMapChunkBackedMapStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * @author jonathan.colt
 */
public class MultiChunkStoreTest {

    @Test
    public void testAllocateAndGet_heap() throws Exception {

        //Files.createTempDirectory("map").toFile().getAbsolutePath()
        MultiChunkStoreInitializer mcsi = new MultiChunkStoreInitializer(new ChunkStoreInitializer());
        MultiChunkStoreConcurrentFilerFactory store = mcsi.initializeMultiByteBufferBacked(
            "boo", new HeapByteBufferFactory(), 10, 512, true, 10, new ByteArrayStripingLocksProvider(10));

        long seed = 12345;
        writeRandom(store, seed);
        readAndAssertRandom(store, seed);
    }

    @Test
    public void testAllocateAndGet_fileBacked() throws Exception {

        File mapDir = Files.createTempDirectory("map").toFile();
        MultiChunkStoreInitializer mcsi = new MultiChunkStoreInitializer(new ChunkStoreInitializer());
        FileBackedMemMappedByteBufferFactory byteBufferFactory = new FileBackedMemMappedByteBufferFactory(mapDir);
        MultiChunkStoreConcurrentFilerFactory multiChunkStore = mcsi.initializeMultiByteBufferBacked(
            "boo", byteBufferFactory, 10, 512, true, 10, new ByteArrayStripingLocksProvider(10));

        long seed = 12345;
        writeRandom(multiChunkStore, seed);

        byteBufferFactory = new FileBackedMemMappedByteBufferFactory(mapDir);
        multiChunkStore = mcsi.initializeMultiByteBufferBacked(
            "boo", byteBufferFactory, 10, 512, true, 10, new ByteArrayStripingLocksProvider(10));

        readAndAssertRandom(multiChunkStore, seed);
    }

    @Test
    public void testMapStore_heap() throws Exception {
        PartitionedMapChunkBackedMapStore<ChunkFiler, Long, Integer> mapStore = buildMapStore(new HeapByteBufferFactory());

        for (int i = 0; i < 1_000; i++) {
            mapStore.add((long) i, i);
        }

        for (int i = 0; i < 1_000; i++) {
            assertEquals(mapStore.get((long) i).intValue(), i);
        }
    }

    @Test
    public void testMapStore_fileBacked() throws Exception {
        File mapDir = Files.createTempDirectory("map").toFile();
        FileBackedMemMappedByteBufferFactory byteBufferFactory = new FileBackedMemMappedByteBufferFactory(mapDir);

        PartitionedMapChunkBackedMapStore<ChunkFiler, Long, Integer> mapStore = buildMapStore(byteBufferFactory);

        for (int i = 0; i < 1_000; i++) {
            mapStore.add((long) i, i);
        }

        mapStore = buildMapStore(byteBufferFactory);

        for (int i = 0; i < 1_000; i++) {
            assertEquals(mapStore.get((long) i).intValue(), i);
        }
    }

    private PartitionedMapChunkBackedMapStore<ChunkFiler, Long, Integer> buildMapStore(
        ByteBufferFactory byteBufferFactory) throws Exception {

        MultiChunkStoreConcurrentFilerFactory multiChunkStore = new MultiChunkStoreInitializer(new ChunkStoreInitializer())
            .initializeMultiByteBufferBacked("boo", byteBufferFactory, 10, 512, true, 10, new ByteArrayStripingLocksProvider(10));

        @SuppressWarnings("unchecked")
        ConcurrentFilerProviderBackedMapChunkProvider<ChunkFiler> factory = new ConcurrentFilerProviderBackedMapChunkProvider<ChunkFiler>(
            8, false, 4, false, 16,
            new ConcurrentFilerProvider[] {
                new ConcurrentFilerProvider<>("booya".getBytes(), multiChunkStore)
            });

        return new PartitionedMapChunkBackedMapStore<>(factory, null, longIntegerMarshaller);
    }

    private void writeRandom(MultiChunkStoreConcurrentFilerFactory store, long seed) throws IOException {
        final Random rand = new Random(seed);
        for (int i = 1; i < 1024; i = i * 2) {
            byte[] key = new byte[i];
            rand.nextBytes(key);
            store.getOrAllocate(key, 8, new MonkeyFilerTransaction<ChunkFiler, Void>() {
                @Override
                public Void commit(ChunkFiler chunkFiler, boolean isNew) throws IOException {
                    chunkFiler.seek(0);
                    FilerIO.writeLong(chunkFiler, rand.nextLong(), "noise");
                    return null;
                }
            });
        }
    }

    private void readAndAssertRandom(MultiChunkStoreConcurrentFilerFactory store, long seed) throws IOException {
        final Random rand = new Random(seed);
        for (int i = 1; i < 1024; i = i * 2) {
            byte[] key = new byte[i];
            rand.nextBytes(key);
            store.getOrAllocate(key, -1, new MonkeyFilerTransaction<ChunkFiler, Void>() {
                @Override
                public Void commit(ChunkFiler chunkFiler, boolean isNew) throws IOException {
                    chunkFiler.seek(0);
                    assertEquals(FilerIO.readLong(chunkFiler, "noise"), rand.nextLong());
                    return null;
                }
            });
        }
    }

    private final KeyValueMarshaller<Long, Integer> longIntegerMarshaller = new KeyValueMarshaller<Long, Integer>() {

        @Override
        public byte[] keyBytes(Long key) {
            return FilerIO.longBytes(key);
        }

        @Override
        public byte[] valueBytes(Integer value) {
            return FilerIO.intBytes(value);
        }

        @Override
        public Long bytesKey(byte[] bytes, int offset) {
            return FilerIO.bytesLong(bytes, offset);
        }

        @Override
        public Integer bytesValue(Long key, byte[] bytes, int offset) {
            return FilerIO.bytesInt(bytes, offset);
        }
    };
}
