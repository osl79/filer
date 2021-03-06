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

import java.util.concurrent.Semaphore;

/**
 *
 * @author jonathan.colt
 */
public class IntIndexSemaphore implements SemaphoreProvider<Integer> {

    private final Semaphore[] semaphores;
    private final int numPermits;

    public IntIndexSemaphore(int numSemaphores, int numPermits) {
        this.numPermits = numPermits;
        this.semaphores = new Semaphore[numSemaphores];
        for (int i = 0; i < numSemaphores; i++) {
            semaphores[i] = new Semaphore(numPermits, true);
        }
    }

    @Override
    public Semaphore semaphore(Integer index) {
        return semaphores[index];
    }

    @Override
    public int getNumPermits() {
        return numPermits;
    }

}
