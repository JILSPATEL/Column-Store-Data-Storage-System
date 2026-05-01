package cdb.storage.persistence;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

public abstract class BasePersister implements ColumnPersister {
    protected static final int HEADER_SIZE = 8;
    protected static final byte ALIVE = 0x00;
    protected static final byte DELETED = (byte) 0xFF;

    protected final String path;

    public BasePersister(String path) {
        this.path = path;
    }

    protected abstract int valueWidth(int tag);

    protected int recordWidth(int tag) {
        return 1 + valueWidth(tag);
    }

    protected long recordOffset(int tag, int physicalIndex) {
        return HEADER_SIZE + (long) physicalIndex * recordWidth(tag);
    }

    @Override
    public int getTag() throws IOException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            return dis.readInt();
        }
    }

    protected void incrementRecordCount() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(4);
            int count = raf.readInt();
            raf.seek(4);
            raf.writeInt(count + 1);
        }
    }

    protected int findPhysicalIndex(int liveRowIndex) throws IOException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            int tag = dis.readInt();
            int total = dis.readInt();
            int width = valueWidth(tag);

            int liveCount = 0;
            for (int physIdx = 0; physIdx < total; physIdx++) {
                byte flag = dis.readByte();
                if (flag != DELETED) {
                    if (liveCount == liveRowIndex) {
                        return physIdx;
                    }
                    liveCount++;
                }
                long remaining = width;
                while (remaining > 0) {
                    long skipped = dis.skip(remaining);
                    if (skipped <= 0) break;
                    remaining -= skipped;
                }
            }
        }
        return -1;
    }

    @Override
    public void delete(int rowIndex) throws IOException {
        int physIdx = findPhysicalIndex(rowIndex);
        if (physIdx < 0) return;

        int tag = getTag();
        long offset = recordOffset(tag, physIdx);
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(offset);
            raf.writeByte(DELETED & 0xFF);
        }
    }
}

