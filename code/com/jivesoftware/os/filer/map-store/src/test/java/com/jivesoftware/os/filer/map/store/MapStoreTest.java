package com.jivesoftware.os.filer.map.store;

import com.jivesoftware.os.filer.io.ByteBufferFactory;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.HeapByteBufferFactory;
import com.jivesoftware.os.filer.map.store.extractors.ExtractIndex;
import com.jivesoftware.os.filer.map.store.extractors.ExtractKey;
import com.jivesoftware.os.filer.map.store.extractors.ExtractPayload;
import java.util.HashSet;
import java.util.Random;
import org.testng.annotations.Test;

/**
 *
 * @author jonathan
 */
public class MapStoreTest {

    @Test(enabled = false)
    public void basicTest() {
        test();
    }

    /**
     *
     * @param _args
     */
    public static void main(final String[] _args) {
        for (int i = 0; i < 1; i++) {
            final int n = i;
            Thread t = new Thread() {

                @Override
                public void run() {
                    test();
                }
            };
            t.start();
        }
    }

    public static void test() {

        int it = 1_000_000;
        int ksize = 4;
        for (int i = 0; i < 10; i++) {
            System.out.println("----------------- " + i + " -----------------");
            test(it, ksize, it, new HeapByteBufferFactory());
        }
    }

    private static boolean test(int _iterations, int keySize, int _maxSize, ByteBufferFactory factory) {

        MapStore pset = MapStore.DEFAULT;
        int payloadSize = 4;

        System.out.println("Upper Bound Max Count = " + pset.absoluteMaxCount(keySize, payloadSize));
        MapChunk set = pset.allocate((byte) 0, (byte) 0, new byte[16], 0, _maxSize, keySize, payloadSize, factory);
        long seed = System.currentTimeMillis();
        int maxCapacity = pset.getCapacity(set);
        System.out.println("ByteSet size in mb for (" + _maxSize + ") is " + (set.size() / 1024d / 1024d) + "mb");

        Random random = new Random(seed);
        long t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            try {
                pset.add(set, (byte) 1, TestUtils.randomLowerCaseAlphaBytes(random, keySize), FilerIO.intBytes(i));
            } catch (OverCapacityException x) {
                break;
            }
        }
        long elapse = System.currentTimeMillis() - t;
        System.out.println("ByteSet add(" + _iterations + ") took " + elapse + " " + pset.getCount(set));

        random = new Random(seed);
        t = System.currentTimeMillis();
        HashSet<String> jset = new HashSet<>(maxCapacity);
        for (int i = 0; i < _iterations; i++) {
            jset.add(new String(TestUtils.randomLowerCaseAlphaBytes(random, keySize)));
        }
        elapse = System.currentTimeMillis() - t;
        System.out.println("JavaHashSet add(" + _iterations + ") took " + elapse);

        random = new Random(seed);
        for (int i = 0; i < pset.getCount(set); i++) {
            byte[] got = pset.get(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize), ExtractPayload.SINGLETON);
            assert got != null : "shouldn't be null";
            //int v = UIO.bytesInt(got);
            //assert v == i : "should be the same";
        }

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            try {
                pset.remove(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize));
            } catch (Exception x) {
                x.printStackTrace();
                break;
            }
        }
        elapse = System.currentTimeMillis() - t;
        System.out.println("ByteSet remove(" + _iterations + ") took " + elapse + " " + pset.getCount(set));

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            try {
                jset.remove(new String(TestUtils.randomLowerCaseAlphaBytes(random, keySize)));
            } catch (Exception x) {
                x.printStackTrace();
                break;
            }
        }
        elapse = System.currentTimeMillis() - t;
        System.out.println("JavaHashSet remove(" + _iterations + ") took " + elapse);

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            if (i % 2 == 0) {
                pset.remove(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize));
            } else {
                pset.add(set, (byte) 1, TestUtils.randomLowerCaseAlphaBytes(random, keySize), FilerIO.intBytes(i));
            }
        }
        elapse = System.currentTimeMillis() - t;
        System.out.println("ByteSet add and remove (" + _maxSize + ") took " + elapse + " " + pset.getCount(set));

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            if (i % 2 == 0) {
                jset.remove(new String(TestUtils.randomLowerCaseAlphaBytes(random, keySize)));
            } else {
                jset.add(new String(TestUtils.randomLowerCaseAlphaBytes(random, keySize)));
            }
        }
        elapse = System.currentTimeMillis() - t;
        System.out.println("JavaHashSet add and remove (" + _maxSize + ") took " + elapse);

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            pset.contains(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize));
        }
        elapse = System.currentTimeMillis() - t;
        System.out.println("ByteSet contains (" + _maxSize + ") took " + elapse + " " + pset.getCount(set));

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            jset.contains(new String(TestUtils.randomLowerCaseAlphaBytes(random, keySize)));
        }
        elapse = System.currentTimeMillis() - t;
        System.out.println("JavaHashSet contains (" + _maxSize + ") took " + elapse);

        random = new Random(seed);
        for (int i = 0; i < _maxSize; i++) {
            if (i % 2 == 0) {
                TestUtils.randomLowerCaseAlphaBytes(random, keySize);
            } else {
                pset.get(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize), ExtractPayload.SINGLETON);
                //assert got == i;
            }
        }
        System.out.println("count " + pset.getCount(set));

        return true;
    }



}
