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

import com.google.common.collect.Lists;
import com.jivesoftware.os.filer.io.CreateFiler;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.GrowFiler;
import com.jivesoftware.os.filer.io.NoOpCreateFiler;
import com.jivesoftware.os.filer.io.NoOpOpenFiler;
import com.jivesoftware.os.filer.io.OpenFiler;
import com.jivesoftware.os.filer.io.api.ChunkTransaction;
import com.jivesoftware.os.filer.io.api.KeyRange;
import com.jivesoftware.os.filer.io.chunk.ChunkFiler;
import com.jivesoftware.os.filer.io.chunk.ChunkStore;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.jivesoftware.os.filer.chunk.store.transaction.TxPowerConstants.SKY_HOOK_POWER_CREATORS;
import static com.jivesoftware.os.filer.chunk.store.transaction.TxPowerConstants.SKY_HOOK_POWER_GROWER;
import static com.jivesoftware.os.filer.chunk.store.transaction.TxPowerConstants.SKY_HOOK_POWER_OPENER;

/**
 * @author jonathan.colt
 * @param <N>
 * @param <H>
 * @param <M>
 */
public class TxNamedMapOfFiler<N extends FPIndex<byte[], N>, H, M> {

    public static final NoOpCreateFiler<ChunkFiler> CHUNK_FILER_CREATOR = new NoOpCreateFiler<>();
    public static final NoOpOpenFiler<ChunkFiler> CHUNK_FILER_OPENER = new NoOpOpenFiler<>();
    public static final TxNamedMapOfFilerOverwriteGrowerProvider<Long, Void> OVERWRITE_GROWER_PROVIDER =
        new TxNamedMapOfFilerOverwriteGrowerProvider<Long, Void>() {

            @Override
            public GrowFiler<Long, Void, ChunkFiler> create(final Long sizeHint) {
                return new GrowFiler<Long, Void, ChunkFiler>() {

                    @Override
                    public Long acquire(Void monkey, ChunkFiler filer, Object lock) throws IOException {
                        return filer.length() < sizeHint ? sizeHint : null;
                    }

                    @Override
                    public void growAndAcquire(Void currentMonkey,
                        ChunkFiler currentFiler,
                        Void newMonkey,
                        ChunkFiler newFiler,
                        Object currentLock,
                        Object newLock
                    ) throws IOException {
                        synchronized (currentLock) {
                            synchronized (newLock) {
                                currentFiler.seek(0);
                                newFiler.seek(0);
                                FilerIO.copy(currentFiler, newFiler, -1);
                            }
                        }
                    }

                    @Override
                    public void release(Void monkey, Object lock) {
                    }
                };
            }
        };

    public static final TxNamedMapOfFilerRewriteGrowerProvider<Long, Void> REWRITE_GROWER_PROVIDER = new TxNamedMapOfFilerRewriteGrowerProvider<Long, Void>() {

        @Override
        public <R> GrowFiler<Long, Void, ChunkFiler> create(final Long sizeHint,
            final ChunkTransaction<Void, R> chunkTransaction,
            final AtomicReference<R> result) {

            return new GrowFiler<Long, Void, ChunkFiler>() {

                @Override
                public Long acquire(Void monkey, ChunkFiler filer, Object lock) throws IOException {
                    return sizeHint;
                }

                @Override
                public void growAndAcquire(Void currentMonkey,
                    ChunkFiler currentFiler,
                    Void newMonkey,
                    ChunkFiler newFiler,
                    Object currentLock,
                    Object newLock) throws IOException {
                    result.set(chunkTransaction.commit(newMonkey, newFiler, newLock));
                }

                @Override
                public void release(Void monkey, Object lock) {
                }
            };
        }
    };

    private final ChunkStore chunkStore;
    private final long constantFP;

    private final CreateFiler<Integer, N, ChunkFiler>[] namedPowerCreator;
    private final OpenFiler<N, ChunkFiler> namedPowerOpener;
    private final GrowFiler<Integer, N, ChunkFiler> namedPowerGrower;
    private final CreateFiler<H, M, ChunkFiler> filerCreator;
    private final OpenFiler<M, ChunkFiler> filerOpener;
    private final TxNamedMapOfFilerOverwriteGrowerProvider<H, M> overwriteGrowerProvider;
    private final TxNamedMapOfFilerRewriteGrowerProvider<H, M> rewriteGrowerProvider;

    public TxNamedMapOfFiler(
        ChunkStore chunkStore,
        long constantFP,
        CreateFiler<Integer, N, ChunkFiler>[] namedPowerCreator,
        OpenFiler<N, ChunkFiler> namedPowerOpener,
        GrowFiler<Integer, N, ChunkFiler> namedPowerGrower,
        CreateFiler<H, M, ChunkFiler> filerCreator,
        OpenFiler<M, ChunkFiler> filerOpener,
        TxNamedMapOfFilerOverwriteGrowerProvider<H, M> overwriteGrowerProvider,
        TxNamedMapOfFilerRewriteGrowerProvider<H, M> rewriteGrowerProvider) {
        this.chunkStore = chunkStore;
        this.constantFP = constantFP;
        this.namedPowerCreator = namedPowerCreator;
        this.namedPowerOpener = namedPowerOpener;
        this.namedPowerGrower = namedPowerGrower;
        this.filerCreator = filerCreator;
        this.filerOpener = filerOpener;
        this.overwriteGrowerProvider = overwriteGrowerProvider;
        this.rewriteGrowerProvider = rewriteGrowerProvider;
    }

    public <R> R readWriteAutoGrow(final byte[] mapName,
        final byte[] filerKey,
        final H sizeHint,
        final ChunkTransaction<M, R> filerTransaction) throws IOException {

        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                chunkStore.newChunk(null, KeyedFPIndexCreator.DEFAULT);
            }
        }
        return chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, R>() {

            @Override
            public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                return monkey.readWriteAutoGrow(chunkStore, chunkPower, 2, SKY_HOOK_POWER_CREATORS[chunkPower], SKY_HOOK_POWER_OPENER,
                    SKY_HOOK_POWER_GROWER, new ChunkTransaction<MapBackedKeyedFPIndex, R>() {

                        @Override
                        public R commit(MapBackedKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {

                            return monkey.readWriteAutoGrow(chunkStore, mapName, null, KeyedFPIndexCreator.DEFAULT, KeyedFPIndexOpener.DEFAULT, null,
                                new ChunkTransaction<PowerKeyedFPIndex, R>() {
                                    @Override
                                    public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                                        int chunkPower = FilerIO.chunkPower(filerKey.length, 0);
                                        return monkey.readWriteAutoGrow(chunkStore, chunkPower, 2, namedPowerCreator[chunkPower], namedPowerOpener,
                                            namedPowerGrower,
                                            new ChunkTransaction<N, R>() {
                                                @Override
                                                public R commit(N monkey, ChunkFiler filer, Object lock) throws IOException {
                                                    // TODO consider using the provided filer in appropriate cases.
                                                    GrowFiler<H, M, ChunkFiler> overwriteGrower = overwriteGrowerProvider.create(sizeHint);
                                                    return monkey.readWriteAutoGrow(chunkStore, filerKey, sizeHint, filerCreator, filerOpener,
                                                        overwriteGrower, filerTransaction);
                                                }
                                            });
                                    }
                                });
                        }
                    });
            }
        });
    }

    public <R> R writeNewReplace(final byte[] mapName,
        final byte[] filerKey,
        final H sizeHint,
        final ChunkTransaction<M, R> chunkTransaction) throws IOException {

        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                chunkStore.newChunk(null, KeyedFPIndexCreator.DEFAULT);
            }
        }
        return chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, R>() {

            @Override
            public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                return monkey.readWriteAutoGrow(chunkStore, chunkPower, 2, SKY_HOOK_POWER_CREATORS[chunkPower], SKY_HOOK_POWER_OPENER,
                    SKY_HOOK_POWER_GROWER,
                    new ChunkTransaction<MapBackedKeyedFPIndex, R>() {

                        @Override
                        public R commit(MapBackedKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {

                            return monkey.readWriteAutoGrow(chunkStore, mapName, null, KeyedFPIndexCreator.DEFAULT, KeyedFPIndexOpener.DEFAULT, null,
                                new ChunkTransaction<PowerKeyedFPIndex, R>() {
                                    @Override
                                    public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                                        int chunkPower = FilerIO.chunkPower(filerKey.length, 0);
                                        return monkey.readWriteAutoGrow(chunkStore, chunkPower, 1, namedPowerCreator[chunkPower], namedPowerOpener,
                                            namedPowerGrower,
                                            new ChunkTransaction<N, R>() {

                                                @Override
                                                public R commit(N monkey, ChunkFiler filer, Object lock) throws IOException {
                                                    // TODO consider using the provided filer in appropriate cases.
                                                    final AtomicReference<R> result = new AtomicReference<>();
                                                    GrowFiler<H, M, ChunkFiler> rewriteGrower = rewriteGrowerProvider.create(sizeHint,
                                                        chunkTransaction, result);
                                                    return monkey.writeNewReplace(chunkStore, filerKey, sizeHint, filerCreator, filerOpener,
                                                        rewriteGrower,
                                                        new ChunkTransaction<M, R>() {

                                                            @Override
                                                            public R commit(M monkey, ChunkFiler filer, Object lock) throws IOException {
                                                                return result.get();
                                                            }
                                                        });
                                                }
                                            });
                                    }
                                });
                        }
                    });
            }
        });
    }

    public <R> R read(final byte[] mapName, final byte[] filerKey, final ChunkTransaction<M, R> filerTransaction) throws IOException {
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                return filerTransaction.commit(null, null, null);
            }
        }
        return chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, R>() {

            @Override
            public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                if (monkey == null || filer == null) {
                    return filerTransaction.commit(null, null, null);
                }

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                return monkey.read(chunkStore, chunkPower, SKY_HOOK_POWER_OPENER,
                    new ChunkTransaction<MapBackedKeyedFPIndex, R>() {

                        @Override
                        public R commit(MapBackedKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                            if (monkey == null || filer == null) {
                                return filerTransaction.commit(null, null, null);
                            }

                            return monkey.read(chunkStore, mapName, KeyedFPIndexOpener.DEFAULT,
                                new ChunkTransaction<PowerKeyedFPIndex, R>() {
                                    @Override
                                    public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                                        if (monkey == null || filer == null) {
                                            return filerTransaction.commit(null, null, null);
                                        }

                                        int chunkPower = FilerIO.chunkPower(filerKey.length, 0);
                                        return monkey.read(chunkStore, chunkPower, namedPowerOpener,
                                            new ChunkTransaction<N, R>() {

                                                @Override
                                                public R commit(N monkey, ChunkFiler filer, Object lock) throws IOException {
                                                    if (monkey == null || filer == null) {
                                                        return filerTransaction.commit(null, null, null);
                                                    }
                                                    // TODO consider using the provided filer in appropriate cases.
                                                    return monkey.read(chunkStore, filerKey, filerOpener, filerTransaction);
                                                }
                                            });
                                    }
                                });
                        }
                    });
            }
        });
    }

    public <R> List<R> readEach(final byte[] mapName, final byte[][] filerKeys, final ChunkTransaction<M, R> filerTransaction) throws IOException {
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                return Collections.emptyList();
            }
        }

        final byte[][][] powerFilerKeys = new byte[64][][];
        for (int i = 0; i < filerKeys.length; i++) {
            byte[] filerKey = filerKeys[i];
            if (filerKey != null) {
                int chunkPower = FilerIO.chunkPower(filerKey.length, 0);
                if (powerFilerKeys[chunkPower] == null) {
                    powerFilerKeys[chunkPower] = new byte[filerKeys.length][];
                }
                powerFilerKeys[chunkPower][i] = filerKey;
            }
        }

        final List<R> result = Lists.newArrayList();
        chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, Void>() {

            @Override
            public Void commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                if (monkey == null || filer == null) {
                    return null;
                }

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                return monkey.read(chunkStore, chunkPower, SKY_HOOK_POWER_OPENER,
                    new ChunkTransaction<MapBackedKeyedFPIndex, Void>() {

                        @Override
                        public Void commit(MapBackedKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                            if (monkey == null || filer == null) {
                                return null;
                            }

                            return monkey.read(chunkStore, mapName, KeyedFPIndexOpener.DEFAULT,
                                new ChunkTransaction<PowerKeyedFPIndex, Void>() {
                                    @Override
                                    public Void commit(PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                                        if (monkey == null || filer == null) {
                                            return null;
                                        }

                                        for (int chunkPower = 0; chunkPower < powerFilerKeys.length; chunkPower++) {
                                            final byte[][] keysForMonkey = powerFilerKeys[chunkPower];
                                            if (keysForMonkey != null) {
                                                monkey.read(chunkStore, chunkPower, namedPowerOpener,
                                                    new ChunkTransaction<N, Void>() {

                                                        @Override
                                                        public Void commit(N monkey, ChunkFiler filer, Object lock) throws IOException {
                                                            if (monkey == null || filer == null) {
                                                                return null;
                                                            }
                                                            // TODO consider using the provided filer in appropriate cases.
                                                            for (byte[] filerKey : keysForMonkey) {
                                                                if (filerKey != null) {
                                                                    R got = monkey.read(chunkStore, filerKey, filerOpener, filerTransaction);
                                                                    if (got != null) {
                                                                        result.add(got);
                                                                    }
                                                                }
                                                            }
                                                            return null;
                                                        }
                                                    });
                                            }
                                        }
                                        return null;
                                    }
                                });
                        }
                    });
            }
        });
        return result;
    }

    public Boolean stream(final byte[] mapName, final List<KeyRange> ranges, final TxStream<byte[], M, ChunkFiler> stream) throws IOException {
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                return true;
            }
        }
        return chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, Boolean>() {

            @Override
            public Boolean commit(final PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                if (monkey == null || filer == null) {
                    return true;
                }

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                return monkey.read(chunkStore, chunkPower, SKY_HOOK_POWER_OPENER,
                    new ChunkTransaction<MapBackedKeyedFPIndex, Boolean>() {

                        @Override
                        public Boolean commit(final MapBackedKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                            if (monkey == null || filer == null) {
                                return true;
                            }

                            return monkey.read(chunkStore, mapName, KeyedFPIndexOpener.DEFAULT,
                                new ChunkTransaction<PowerKeyedFPIndex, Boolean>() {

                                    @Override
                                    public Boolean commit(final PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                                        if (monkey == null || filer == null) {
                                            return true;
                                        }
                                        return monkey.stream(null, new KeysStream<Integer>() {

                                            @Override
                                            public boolean stream(Integer key) throws IOException {
                                                Boolean result = monkey.read(chunkStore, key, namedPowerOpener,
                                                    new ChunkTransaction<N, Boolean>() {

                                                        @Override
                                                        public Boolean commit(final N monkey, ChunkFiler filer, Object lock)
                                                        throws IOException {
                                                            if (monkey == null || filer == null) {
                                                                return true;
                                                            }
                                                            return monkey.stream(ranges, new KeysStream<byte[]>() {

                                                                @Override
                                                                public boolean stream(final byte[] key) throws IOException {
                                                                    Boolean result = monkey.read(chunkStore, key, filerOpener,
                                                                        new ChunkTransaction<M, Boolean>() {

                                                                            @Override
                                                                            public Boolean commit(M monkey, ChunkFiler filer, Object lock) throws IOException {
                                                                                return stream.stream(key, monkey, filer, lock);
                                                                            }
                                                                        });
                                                                    return result;
                                                                }
                                                            });
                                                        }
                                                    });
                                                return result;
                                            }
                                        });
                                    }
                                });

                        }
                    });
            }
        });
    }

    public Boolean streamKeys(final byte[] mapName, final List<KeyRange> ranges, final TxStreamKeys<byte[]> stream) throws IOException {
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                return true;
            }
        }
        return chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, Boolean>() {

            @Override
            public Boolean commit(final PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                if (monkey == null || filer == null) {
                    return true;
                }

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                return monkey.read(chunkStore, chunkPower, SKY_HOOK_POWER_OPENER,
                    new ChunkTransaction<MapBackedKeyedFPIndex, Boolean>() {

                        @Override
                        public Boolean commit(final MapBackedKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                            if (monkey == null || filer == null) {
                                return true;
                            }

                            return monkey.read(chunkStore, mapName, KeyedFPIndexOpener.DEFAULT,
                                new ChunkTransaction<PowerKeyedFPIndex, Boolean>() {

                                    @Override
                                    public Boolean commit(final PowerKeyedFPIndex monkey, ChunkFiler filer, Object lock) throws IOException {
                                        if (monkey == null || filer == null) {
                                            return true;
                                        }
                                        return monkey.stream(null, new KeysStream<Integer>() {

                                            @Override
                                            public boolean stream(Integer key) throws IOException {
                                                Boolean result = monkey.read(chunkStore, key, namedPowerOpener,
                                                    new ChunkTransaction<N, Boolean>() {

                                                        @Override
                                                        public Boolean commit(final N monkey, ChunkFiler filer, Object lock)
                                                        throws IOException {
                                                            if (monkey == null || filer == null) {
                                                                return true;
                                                            }
                                                            Boolean result = monkey.stream(ranges, new KeysStream<byte[]>() {

                                                                @Override
                                                                public boolean stream(final byte[] key) throws IOException {
                                                                    return stream.stream(key);
                                                                }
                                                            });
                                                            return result;
                                                        }
                                                    });
                                                return result;
                                            }
                                        });
                                    }
                                });

                        }
                    });
            }
        });

    }
}
