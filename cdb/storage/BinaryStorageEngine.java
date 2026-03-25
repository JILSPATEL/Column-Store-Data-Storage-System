package cdb.storage;

import cdb.ddl.ColumnSchema;
import cdb.ddl.TableSchema;
import cdb.util.FileUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * BinaryStorageEngine – a fixed-width, column-oriented binary persistence layer.
 *
 * FILE FORMAT  (.bin)
 * ─────────────────────────────────────────────────────────────────────────────
 *  HEADER (8 bytes, always at offset 0)
 *    bytes 0-3  : type tag  (int32, big-endian)  – one of the TYPE_* constants
 *    bytes 4-7  : record count (int32, big-endian) – total records ever written
 *                 (live + tombstoned)
 *
 *  RECORDS  (each record = 1 + WIDTH bytes)
 *    byte  0    : tombstone flag – 0x00 = alive, 0xFF = deleted
 *    bytes 1…W  : raw value bytes (big-endian, written by DataOutputStream)
 *
 * Supported numeric types and their widths:
 *   BYTE       →  1 byte   (signed 8-bit)
 *   SHORT      →  2 bytes  (signed 16-bit)
 *   INT        →  4 bytes  (signed 32-bit)
 *   LONG       →  8 bytes  (signed 64-bit)
 *   FLOAT      →  4 bytes  (IEEE-754 single)
 *   DOUBLE     →  8 bytes  (IEEE-754 double)
 *   BOOLEAN    →  1 byte   (0=false, 1=true)
 *   BIGDECIMAL → 20 bytes  (int32 scale + int64 unscaled + 8-byte padding)
 *
 * The interface still passes values as String (same contract as TextStorageEngine)
 * so QueryEngine and the rest of the system need no changes.
 */
public class BinaryStorageEngine implements StorageEngine {

    // ── Type tags ────────────────────────────────────────────────────────────
    private static final int TYPE_BYTE       = 1;
    private static final int TYPE_SHORT      = 2;
    private static final int TYPE_INT        = 3;
    private static final int TYPE_LONG       = 4;
    private static final int TYPE_FLOAT      = 5;
    private static final int TYPE_DOUBLE     = 6;
    private static final int TYPE_BOOLEAN    = 7;
    private static final int TYPE_BIGDECIMAL = 8;

    // ── Tombstone constants ───────────────────────────────────────────────────
    private static final byte ALIVE    = 0x00;
    private static final byte DELETED  = (byte) 0xFF;

    // ── File layout constants ─────────────────────────────────────────────────
    private static final int HEADER_SIZE = 8;  // 4B type tag + 4B record count

    private final String tablesDir;

    public BinaryStorageEngine(String dataDir) {
        this.tablesDir = dataDir + "/tables";
        FileUtils.ensureDirectory(this.tablesDir);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String getColumnPath(String table, String column) {
        return tablesDir + "/" + table + "/" + column + ".bin";
    }

    /** Map a SQL type keyword (case-insensitive) to its integer tag. */
    private int typeTag(String sqlType) {
        switch (sqlType.toUpperCase()) {
            case "BYTE":       return TYPE_BYTE;
            case "SHORT":      return TYPE_SHORT;
            case "INT":
            case "INTEGER":    return TYPE_INT;
            case "LONG":
            case "BIGINT":     return TYPE_LONG;
            case "FLOAT":
            case "REAL":       return TYPE_FLOAT;
            case "DOUBLE":
            case "DECIMAL":    return TYPE_DOUBLE;
            case "BOOLEAN":
            case "BOOL":       return TYPE_BOOLEAN;
            case "BIGDECIMAL":
            case "NUMERIC":    return TYPE_BIGDECIMAL;
            default:
                throw new IllegalArgumentException(
                    "Unsupported numeric type: " + sqlType +
                    ". BinaryStorageEngine supports only numeric types " +
                    "(BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BOOLEAN, BIGDECIMAL).");
        }
    }

    /** Returns the byte-width of a single value (excluding the tombstone flag). */
    private int valueWidth(int tag) {
        switch (tag) {
            case TYPE_BYTE:       return 1;
            case TYPE_SHORT:      return 2;
            case TYPE_INT:        return 4;
            case TYPE_LONG:       return 8;
            case TYPE_FLOAT:      return 4;
            case TYPE_DOUBLE:     return 8;
            case TYPE_BOOLEAN:    return 1;
            case TYPE_BIGDECIMAL: return 20; // 4B scale + 8B unscaled long + 8B pad
            default: throw new IllegalStateException("Unknown type tag: " + tag);
        }
    }

    /** Full byte-width of one record on disk (tombstone + value). */
    private int recordWidth(int tag) {
        return 1 + valueWidth(tag);
    }

    /**
     * Read the type tag from an existing .bin file header.
     * Requires: file exists.
     */
    private int readTypeTag(String path) throws IOException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            return dis.readInt();
        }
    }

    /**
     * Atomically increment the record count stored in the header.
     */
    private void incrementRecordCount(String path) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(4);                       // byte offset of record count
            int count = raf.readInt();
            raf.seek(4);
            raf.writeInt(count + 1);
        }
    }

    /**
     * Byte offset in the file where record at physical index `physicalIndex` starts.
     * Physical index counts ALL records (live + tombstoned) starting from 0.
     */
    private long recordOffset(int tag, int physicalIndex) {
        return HEADER_SIZE + (long) physicalIndex * recordWidth(tag);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Encoding / Decoding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Encode a String value into its binary representation for the given type tag.
     * Returns exactly valueWidth(tag) bytes.
     */
    private byte[] encode(int tag, String value) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        switch (tag) {
            case TYPE_BYTE:
                dos.writeByte(Byte.parseByte(value.trim()));
                break;
            case TYPE_SHORT:
                dos.writeShort(Short.parseShort(value.trim()));
                break;
            case TYPE_INT:
                dos.writeInt(Integer.parseInt(value.trim()));
                break;
            case TYPE_LONG:
                dos.writeLong(Long.parseLong(value.trim()));
                break;
            case TYPE_FLOAT:
                dos.writeFloat(Float.parseFloat(value.trim()));
                break;
            case TYPE_DOUBLE:
                dos.writeDouble(Double.parseDouble(value.trim()));
                break;
            case TYPE_BOOLEAN:
                boolean b = value.trim().equalsIgnoreCase("true")
                         || value.trim().equals("1");
                dos.writeByte(b ? 1 : 0);
                break;
            case TYPE_BIGDECIMAL: {
                BigDecimal bd = new BigDecimal(value.trim());
                int scale = bd.scale();
                // Store unscaled value clamped to long for simplicity
                long unscaled = bd.unscaledValue().longValueExact();
                dos.writeInt(scale);      // 4 bytes
                dos.writeLong(unscaled);  // 8 bytes
                // 8 bytes padding (reserved for future full-precision storage)
                dos.writeLong(0L);
                break;
            }
            default:
                throw new IllegalStateException("Unknown type tag: " + tag);
        }

        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Decode bytes at the current position in a DataInputStream to a String.
     */
    private String decode(int tag, DataInputStream dis) throws IOException {
        switch (tag) {
            case TYPE_BYTE:
                return String.valueOf(dis.readByte());
            case TYPE_SHORT:
                return String.valueOf(dis.readShort());
            case TYPE_INT:
                return String.valueOf(dis.readInt());
            case TYPE_LONG:
                return String.valueOf(dis.readLong());
            case TYPE_FLOAT:
                return String.valueOf(dis.readFloat());
            case TYPE_DOUBLE:
                return String.valueOf(dis.readDouble());
            case TYPE_BOOLEAN:
                return dis.readByte() == 1 ? "true" : "false";
            case TYPE_BIGDECIMAL: {
                int scale    = dis.readInt();
                long unscaled = dis.readLong();
                dis.readLong(); // consume padding
                BigDecimal bd = new BigDecimal(BigInteger.valueOf(unscaled), scale);
                return bd.toPlainString();
            }
            default:
                throw new IllegalStateException("Unknown type tag: " + tag);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StorageEngine interface
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void createTable(TableSchema schema) throws IOException {
        String tablePath = tablesDir + "/" + schema.getTableName();
        FileUtils.ensureDirectory(tablePath);

        for (ColumnSchema col : schema.getColumns()) {
            int tag  = typeTag(col.getType());
            String path = getColumnPath(schema.getTableName(), col.getName());
            // Write a fresh header: type tag (4B) + record count 0 (4B)
            try (DataOutputStream dos =
                     new DataOutputStream(new FileOutputStream(path, false))) {
                dos.writeInt(tag);
                dos.writeInt(0);
            }
        }
    }

    @Override
    public void appendValue(String table, String column, String value) throws IOException {
        String path = getColumnPath(table, column);
        int tag     = readTypeTag(path);
        byte[] valueBytes = encode(tag, value);

        // Append: tombstone flag (alive) + encoded value bytes
        try (FileOutputStream fos = new FileOutputStream(path, true)) {
            fos.write(ALIVE);
            fos.write(valueBytes);
        }

        // Increment record count in header
        incrementRecordCount(path);
    }

    @Override
    public List<String> readColumn(String table, String column) throws IOException {
        String path = getColumnPath(table, column);
        File   file = new File(path);
        if (!file.exists()) return new ArrayList<>();

        List<String> results = new ArrayList<>();

        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            int tag   = dis.readInt();             // type tag
            int total = dis.readInt();             // total records
            int width = valueWidth(tag);

            for (int i = 0; i < total; i++) {
                byte flag = dis.readByte();        // tombstone flag
                if (flag == DELETED) {
                    // skip the value bytes for this deleted record
                    long skipped = dis.skip(width);
                    if (skipped != width) {
                        // Manually skip remaining if skip() returned less
                        long remaining = width - skipped;
                        while (remaining > 0) {
                            remaining -= dis.skip(remaining);
                        }
                    }
                } else {
                    // alive record – decode value
                    results.add(decode(tag, dis));
                }
            }
        }

        return results;
    }

    /**
     * Find the physical (file-level) index of the Nth live record.
     * Returns -1 if not found.
     */
    private int findPhysicalIndex(String path, int liveRowIndex) throws IOException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            int tag   = dis.readInt();
            int total = dis.readInt();
            int width = valueWidth(tag);

            int liveCount = 0;
            for (int physIdx = 0; physIdx < total; physIdx++) {
                byte flag = dis.readByte();
                if (flag != DELETED) {
                    if (liveCount == liveRowIndex) {
                        return physIdx;   // found it
                    }
                    liveCount++;
                }
                // skip value bytes for this record
                long remaining = width;
                while (remaining > 0) {
                    long skipped = dis.skip(remaining);
                    if (skipped <= 0) break;
                    remaining -= skipped;
                }
            }
        }
        return -1; // not found
    }

    @Override
    public void updateValue(String table, String column, int rowIndex, String value)
            throws IOException {
        String path = getColumnPath(table, column);
        File   file = new File(path);
        if (!file.exists()) return;

        int tag = readTypeTag(path);
        int physIdx = findPhysicalIndex(path, rowIndex);
        if (physIdx < 0) return; // row index out of range

        byte[] newBytes = encode(tag, value);
        long   offset   = recordOffset(tag, physIdx) + 1; // +1 to skip tombstone byte

        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(offset);
            raf.write(newBytes);
        }
    }

    @Override
    public void deleteRow(String table, int rowIndex) throws IOException {
        File tableDir = new File(tablesDir + "/" + table);
        File[] colFiles = tableDir.listFiles(
            f -> f.isFile() && f.getName().endsWith(".bin")
        );
        if (colFiles == null) return;

        for (File colFile : colFiles) {
            String path = colFile.getAbsolutePath();
            int tag     = readTypeTag(path);
            int physIdx = findPhysicalIndex(path, rowIndex);
            if (physIdx < 0) continue;

            long offset = recordOffset(tag, physIdx); // tombstone is at this exact offset
            try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
                raf.seek(offset);
                raf.writeByte(DELETED & 0xFF);  // write 0xFF tombstone
            }
        }
    }

    @Override
    public void dropTable(String table) throws IOException {
        File tableDir = new File(tablesDir + "/" + table);
        FileUtils.deleteDirectory(tableDir);
    }
}
