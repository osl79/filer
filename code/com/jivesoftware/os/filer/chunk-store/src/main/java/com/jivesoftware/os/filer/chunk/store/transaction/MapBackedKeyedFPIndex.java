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
package com.jivesoftware.os.filer.chunk.store.transaction;

import com.jivesoftware.os.filer.chunk.store.ChunkFiler;
import com.jivesoftware.os.filer.chunk.store.ChunkStore;
import com.jivesoftware.os.filer.chunk.store.ChunkTransaction;
import com.jivesoftware.os.filer.io.CreateFiler;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.OpenFiler;
import com.jivesoftware.os.filer.map.store.MapContext;
import com.jivesoftware.os.filer.map.store.MapStore;
import java.io.IOException;

/**
 *
 * @author jonathan.colt
 */
public class MapBackedKeyedFPIndex implements KeyedFPIndex<byte[]>, KeyedFPIndexUtil.BackingFPIndex<byte[]> {

    private final ChunkStore backingChunkStore;
    private final long backingFP;
    private final int numPermit = 64;
    private final ByteArrayStripingSemaphore stripingSemaphores = new ByteArrayStripingSemaphore(64, numPermit);

    public MapBackedKeyedFPIndex(ChunkStore chunkStore, long fp) {
        this.backingChunkStore = chunkStore;
        this.backingFP = fp;

    }

    @Override
    public long get(final byte[] key) throws IOException {
        return backingChunkStore.execute(backingFP, new MapOpener(), new ChunkTransaction<MapContext, Long>() {

            @Override
            public Long commit(MapContext monkey, ChunkFiler filer) throws IOException {
                synchronized (monkey) {
                    long ai = MapStore.INSTANCE.get(filer, monkey, key);
                    if (ai < 0) {
                        return -1L;
                    }
                    return FilerIO.bytesLong(MapStore.INSTANCE.getPayload(filer, monkey, ai));
                }
            }
        });
    }

    @Override
    public void set(final byte[] key, final long fp) throws IOException {
        backingChunkStore.execute(backingFP, null, new ChunkTransaction<MapContext, Void>() {

            @Override
            public Void commit(MapContext monkey, ChunkFiler filer) throws IOException {
                synchronized (monkey) {
                    MapStore.INSTANCE.add(filer, monkey, (byte) 1, key, FilerIO.longBytes(fp));
                }
                return null;
            }
        });
    }

    @Override
    public <H, M, R> R commit(ChunkStore chunkStore,
        byte[] key,
        H hint,
        CreateFiler<H, M, ChunkFiler> creator,
        OpenFiler<M, ChunkFiler> opener,
        GrowFiler<H, M, ChunkFiler> growFiler,
        ChunkTransaction<M, R> filerTransaction) throws IOException {

        return KeyedFPIndexUtil.INSTANCE.commit(this, stripingSemaphores, chunkStore, key, hint, creator, opener, growFiler, filerTransaction);
    }

}
