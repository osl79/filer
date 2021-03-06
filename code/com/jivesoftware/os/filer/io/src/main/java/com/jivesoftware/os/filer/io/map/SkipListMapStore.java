package com.jivesoftware.os.filer.io.map;

import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.api.KeyRange;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * This is a skip list which is backed by a byte[]. This collection wastes quite a bit of space in favor of page in and out speed. May make sense to use a
 * compression strategy when sending over the wire. Each entry you sadd cost a fixed amount of space. This can be calculate by the following: entrySize =
 * entrySize+(1+(entrySize*maxColumHeight); maxColumHeight = ? where 2 ^ ? is > maxCount
 *
 * The key composed of all BYTE.MIN_VALUE is typically reserved as the head of the list.
 *
 * @author jonathan
 */
public class SkipListMapStore {

    static public final SkipListMapStore INSTANCE = new SkipListMapStore();

    private SkipListMapStore() {
    }

    private static final int cColumKeySize = 4; // stores the int index of the key it points to !! could make dynamic to save space

    public long computeFilerSize(
        int maxCount,
        int keySize,
        boolean variableKeySizes,
        int payloadSize,
        byte maxColumnHeight) throws IOException {
        maxCount += 2; // room for a head key and a tail key
        payloadSize = payloadSize(maxColumnHeight) + payloadSize;
        return MapStore.INSTANCE.computeFilerSize(maxCount, keySize, variableKeySizes, payloadSize, false);
    }

    int payloadSize(byte maxHeight) {
        return 1 + (cColumKeySize * maxHeight);
    }

    public SkipListMapContext create(
        int _maxCount,
        byte[] headKey,
        int keySize,
        boolean variableKeySizes,
        int _payloadSize,
        byte maxColumnHeight,
        SkipListComparator _valueComparator,
        Filer filer) throws IOException {
        if (headKey.length != keySize) {
            throw new RuntimeException("Expected that headKey.length == keySize");
        }
        _maxCount += 2;
        int columnAndPayload = payloadSize(maxColumnHeight) + _payloadSize;
        MapContext mapContext = MapStore.INSTANCE.create(_maxCount, keySize, variableKeySizes, columnAndPayload, false, filer);
        int headKeyIndex = (int) MapStore.INSTANCE.add(filer, mapContext, (byte) 1, headKey,
            newColumn(new byte[_payloadSize], maxColumnHeight, maxColumnHeight));
        SkipListMapContext context = new SkipListMapContext(mapContext, headKeyIndex, headKey, _valueComparator);
        return context;
    }

    public SkipListMapContext open(byte[] headKey,
        SkipListComparator _valueComparator,
        Filer filer) throws IOException {

        MapContext mapContext = MapStore.INSTANCE.open(filer);
        int headKeyIndex = (int) MapStore.INSTANCE.get(filer, mapContext, headKey);
        if (headKeyIndex == -1) {
            throw new RuntimeException("SkipListSetPage:Invalid Page!");
        }
        SkipListMapContext context = new SkipListMapContext(mapContext, headKeyIndex, headKey, _valueComparator);
        return context;
    }

    public long getCount(Filer filer, SkipListMapContext page) throws IOException {
        return MapStore.INSTANCE.getCount(filer) - 1; // -1 because of head
    }

    public long add(Filer filer, SkipListMapContext context, byte[] key, byte[] _payload) throws IOException {

        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index != -1) { // aready exists so just update payload
            MapStore.INSTANCE.setPayloadAtIndex(filer, context.mapContext, index, columnSize(context.maxHeight), _payload, 0, _payload.length);
            return index;
        }

        byte[] newColumn = newColumn(_payload, context.maxHeight, (byte) -1); // create a new colum for a new key
        final int insertsIndex = (int) MapStore.INSTANCE.add(filer, context.mapContext, (byte) 1, key, newColumn);

        int level = context.maxHeight - 1;
        int ilevel = columnLength(filer, context, insertsIndex);
        int atIndex = context.headIndex;
        while (level > 0) {
            int nextIndex = rcolumnLevel(filer, context, atIndex, level);
            if (nextIndex == -1) {
                if (level < ilevel) {
                    wcolumnLevel(filer, context, atIndex, level, insertsIndex);
                    if (level == 1) {
                        wcolumnLevel(filer, context, insertsIndex, 0, atIndex);
                    }
                }
                level--;
            } else {
                int compare = context.keyComparator.compare(MapStore.INSTANCE.getKey(filer, context.mapContext, nextIndex),
                    MapStore.INSTANCE.getKey(filer, context.mapContext, insertsIndex));
                if (compare == 0) {
                    throw new RuntimeException("should be impossible");
                } else if (compare < 0) { // keep looking forward
                    atIndex = nextIndex;
                } else { // insert
                    if (level < ilevel) {
                        wcolumnLevel(filer, context, insertsIndex, level, nextIndex);
                        wcolumnLevel(filer, context, atIndex, level, insertsIndex);
                        if (level == 1) {
                            wcolumnLevel(filer, context, insertsIndex, 0, atIndex);
                            wcolumnLevel(filer, context, nextIndex, 0, insertsIndex);
                        }
                    }
                    level--;
                }
            }
        }
        return insertsIndex;
    }

    public byte[] findWouldInsertAtOrAfter(Filer filer, SkipListMapContext context, byte[] _key) throws IOException {

        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, _key);
        if (index != -1) { // aready exists so return self
            return _key;
        }
        // create a new colum for a new key

        int level = context.maxHeight - 1;
        int atIndex = context.headIndex;
        while (level > 0) {
            int nextIndex = rcolumnLevel(filer, context, atIndex, level);
            if (nextIndex == -1) {
                if (level == 1) {
                    return MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, atIndex);
                }
                level--;
            } else {
                int compare = context.keyComparator.compare(MapStore.INSTANCE.getKey(filer, context.mapContext, nextIndex),
                    _key);
                if (compare == 0) {
                    throw new RuntimeException("should be impossible");
                } else if (compare < 0) { // keep looking forward
                    atIndex = nextIndex;
                } else { // insert
                    if (level == 1) {
                        if (atIndex == context.headIndex) {
                            return null;
                        }
                        return MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, atIndex);
                    }
                    level--;
                }
            }
        }
        return null;
    }

    public byte[] getFirst(Filer filer, SkipListMapContext page) throws IOException {
        int firstIndex = rcolumnLevel(filer, page, page.headIndex, 1);
        if (firstIndex == -1) {
            return null;
        } else {
            return MapStore.INSTANCE.getKeyAtIndex(filer, page.mapContext, firstIndex);
        }
    }

    public void remove(Filer filer, SkipListMapContext context, byte[] _key) throws IOException {
        int removeIndex = (int) MapStore.INSTANCE.get(filer, context.mapContext, _key);
        if (removeIndex == -1) { // doesn't exists so return
            return;
        }

        int level = context.maxHeight - 1;
        int atIndex = context.headIndex;
        while (level > 0) {
            int nextIndex = rcolumnLevel(filer, context, atIndex, level);
            if (nextIndex == -1) {
                level--;
            } else {
                int compare = context.keyComparator.compare(MapStore.INSTANCE.getKey(filer, context.mapContext, nextIndex),
                    MapStore.INSTANCE.getKey(filer, context.mapContext, removeIndex));
                if (compare == 0) {
                    while (level > -1) {
                        int removesNextIndex = rcolumnLevel(filer, context, removeIndex, level);
                        wcolumnLevel(filer, context, atIndex, level, removesNextIndex);
                        if (level == 0) {
                            wcolumnLevel(filer, context, removesNextIndex, level, atIndex);
                        }
                        level--;
                    }

                } else if (compare < 0) {
                    atIndex = nextIndex;
                } else {
                    level--;
                }
            }
        }
        MapStore.INSTANCE.remove(filer, context.mapContext, _key);
    }

    public byte[] getPrior(Filer filer, SkipListMapContext context, byte[] key) throws IOException {
        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index == -1) {
            return null;
        } else {
            int pi = rcolumnLevel(filer, context, index, 0);
            if (pi == -1) {
                return null;
            } else {
                byte[] got = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, pi);
                if (Arrays.equals(context.headKey, got)) {
                    return null; // don't give out head key
                }
                return got;
            }
        }
    }

    public byte[] getNextKey(Filer filer, SkipListMapContext context, byte[] key) throws IOException {
        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index == -1) {
            return null;
        } else {
            int nextIndex = rcolumnLevel(filer, context, index, 1);
            if (nextIndex == -1) {
                return null;
            } else {
                return MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, nextIndex);
            }
        }
    }

    public byte[] getExistingPayload(Filer filer, SkipListMapContext context, byte[] key) throws IOException {
        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index == -1) {
            return null;
        } else {
            return getColumnPayload(filer, context, index, context.maxHeight);
        }
    }

    public boolean streamKeys(final Filer filer, final SkipListMapContext context, final Object lock,
        List<KeyRange> ranges, MapStore.KeyStream stream) throws IOException {
        if (ranges == null) {
            for (int index = 0; index < context.mapContext.capacity; index++) {
                if (index == context.headIndex) { // Barf
                    continue;
                }
                byte[] key;
                synchronized (lock) {
                    key = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, index);
                }
                if (key != null) {
                    if (!stream.stream(key)) {
                        return false;
                    }
                }
            }

        } else {
            for (KeyRange range : ranges) {
                byte[] key = findWouldInsertAtOrAfter(filer, context, range.getStartInclusiveKey());
                if (key == null) {
                    key = getNextKey(filer, context, context.headKey);
                }
                if (key != null) {
                    if (range.contains(key)) {
                        if (!stream.stream(key)) {
                            return false;
                        }
                    }
                    byte[] next = getNextKey(filer, context, key);
                    while (next != null && range.contains(next)) {
                        if (!stream.stream(next)) {
                            return false;
                        }
                        next = getNextKey(filer, context, next);
                    }
                }
            }

        }
        return true;
    }

    /**
     * this is a the lazy impl... this can be highly optimized when we have time!
     *
     */
    public void copyTo(Filer f, SkipListMapContext from, final Filer t, final SkipListMapContext to, MapStore.CopyToStream stream) throws IOException {
        for (int fromIndex = 0; fromIndex < from.mapContext.capacity; fromIndex++) {
            if (fromIndex == from.headIndex) { // Barf
                continue;
            }

            long ai = MapStore.INSTANCE.index(fromIndex, from.mapContext.entrySize);
            byte mode = MapStore.INSTANCE.read(f, (int) ai);
            if (mode == MapStore.cNull) {
                continue;
            }
            if (mode == MapStore.cSkip) {
                continue;
            }

            byte[] key = MapStore.INSTANCE.getKey(f, from.mapContext, fromIndex);
            byte[] payload = getColumnPayload(f, from, fromIndex, from.maxHeight);
            long toIndex = add(t, to, key, payload);

            if (stream != null) {
                stream.copied(fromIndex, toIndex);
            }
        }
    }

    private byte[] newColumn(byte[] _payload, int _maxHeight, byte _height) {
        if (_height <= 0) {
            byte newH = 2;
            while (Math.random() > 0.5d) { // could pick a rand number bewteen 1 and 32 instead
                if (newH + 1 >= _maxHeight) {
                    break;
                }
                newH++;
            }
            _height = newH;
        }
        byte[] column = new byte[1 + (_maxHeight * cColumKeySize) + _payload.length];
        column[0] = _height;
        for (int i = 0; i < _maxHeight; i++) {
            setColumKey(column, i, FilerIO.intBytes(-1)); // fill with nulls ie -1
        }
        System.arraycopy(_payload, 0, column, 1 + (_maxHeight * cColumKeySize), _payload.length);
        return column;
    }

    private void setColumKey(byte[] _column, int _h, byte[] _key) {
        System.arraycopy(_key, 0, _column, 1 + (_h * cColumKeySize), cColumKeySize);
    }

    private byte columnLength(Filer f, SkipListMapContext context, int setIndex) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        return MapStore.INSTANCE.read(f, MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize));
    }

    private int rcolumnLevel(Filer f, SkipListMapContext context, int setIndex, int level) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        int offset = (int) MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize) + 1 + (level * cColumKeySize);
        return MapStore.INSTANCE.readInt(f, offset);
    }

    private void wcolumnLevel(Filer f, SkipListMapContext context, int setIndex, int level, int v) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        int offset = (int) MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize) + 1 + (level * cColumKeySize);
        MapStore.INSTANCE.writeInt(f, offset, v);
    }

    private int columnSize(int maxHeight) {
        return 1 + (cColumKeySize * maxHeight);
    }

    private byte[] getColumnPayload(Filer f, SkipListMapContext context, int setIndex, int maxHeight) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        int startOfPayload = (int) MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize);
        int size = context.mapContext.payloadSize - columnSize(maxHeight);
        byte[] payload = new byte[size];
        MapStore.INSTANCE.read(f, startOfPayload + 1 + (maxHeight * cColumKeySize), payload, 0, size);
        return payload;
    }

    public void toSysOut(Filer f, SkipListMapContext context, BytesToString keyToString) throws IOException {
        if (keyToString == null) {
            keyToString = new BytesToBytesString();
        }
        int atIndex = context.headIndex;
        int count = 0;
        while (atIndex != -1) {
            toSysOut(f, context, atIndex, keyToString);
            atIndex = rcolumnLevel(f, context, atIndex, 1);

            if (count > MapStore.INSTANCE.getCount(f)) {
                System.out.println("BAD Panda! Cyclic");
                break;
            }
            count++;
        }
    }

    /**
     *
     */
    static public interface BytesToString {

        /**
         *
         * @param bytes
         * @return
         */
        String bytesToString(byte[] bytes);
    }

    /**
     *
     */
    static public class BytesToDoubleString implements BytesToString {

        /**
         *
         * @param bytes
         * @return
         */
        @Override
        public String bytesToString(byte[] bytes) {
            return Double.toString(FilerIO.bytesDouble(bytes));
        }
    }

    /**
     *
     */
    static public class BytesToBytesString implements BytesToString {

        /**
         *
         * @param bytes
         * @return
         */
        @Override
        public String bytesToString(byte[] bytes) {
            return Arrays.toString(bytes);
        }
    }

    private void toSysOut(Filer filer, SkipListMapContext context, int index, BytesToString keyToString) throws IOException {
        byte[] key = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, index);
        System.out.print("\ti:" + index);
        System.out.print("\tv:" + keyToString.bytesToString(key) + " - \t");
        int l = columnLength(filer, context, index);
        for (int i = 0; i < l; i++) {
            if (i != 0) {
                System.out.print("),\t" + i + ":(");
            } else {
                System.out.print(i + ":(");
            }
            int ni = rcolumnLevel(filer, context, index, i);
            if (ni == -1) {
                System.out.print("NULL");

            } else {
                byte[] nkey = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, ni);
                if (nkey == null) {
                    System.out.print(ni + "=???");
                } else {
                    System.out.print(ni + "=" + keyToString.bytesToString(nkey));
                }

            }
        }
        System.out.println(")");
    }
}
