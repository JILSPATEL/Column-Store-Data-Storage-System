package cdb.storage.persistence;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class NumericalPersister extends BasePersister {

    public static final int TYPE_BYTE       = 1;
    public static final int TYPE_SHORT      = 2;
    public static final int TYPE_INT        = 3;
    public static final int TYPE_LONG       = 4;
    public static final int TYPE_FLOAT      = 5;
    public static final int TYPE_DOUBLE     = 6;
    public static final int TYPE_BOOLEAN    = 7;
    public static final int TYPE_BIGDECIMAL = 8;

    public NumericalPersister(String path) {
        super(path);
    }

    @Override
    protected int valueWidth(int tag) {
        switch (tag) {
            case TYPE_BYTE:       return 1;
            case TYPE_SHORT:      return 2;
            case TYPE_INT:        return 4;
            case TYPE_LONG:       return 8;
            case TYPE_FLOAT:      return 4;
            case TYPE_DOUBLE:     return 8;
            case TYPE_BOOLEAN:    return 1;
            case TYPE_BIGDECIMAL: return 20;
            default: throw new IllegalStateException("Unknown numerical type tag: " + tag);
        }
    }

    @Override
    public void create(int tag) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            dos.writeInt(tag);
            dos.writeInt(0);
        }
    }

    @Override
    public void append(String value) throws IOException {
        int tag = getTag();
        byte[] bytes = encode(tag, value);
        try (FileOutputStream fos = new FileOutputStream(path, true)) {
            fos.write(ALIVE);
            fos.write(bytes);
        }
        incrementRecordCount();
    }

    @Override
    public List<String> readAll() throws IOException {
        List<String> results = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            int tag = dis.readInt();
            int total = dis.readInt();
            int width = valueWidth(tag);

            for (int i = 0; i < total; i++) {
                byte flag = dis.readByte();
                if (flag == DELETED) {
                    dis.skipBytes(width);
                } else {
                    results.add(decode(tag, dis));
                }
            }
        }
        return results;
    }

    @Override
    public void update(int rowIndex, String value) throws IOException {
        int physIdx = findPhysicalIndex(rowIndex);
        if (physIdx < 0) return;

        int tag = getTag();
        byte[] bytes = encode(tag, value);
        long offset = recordOffset(tag, physIdx) + 1;
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(offset);
            raf.write(bytes);
        }
    }

    private byte[] encode(int tag, String value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
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
                boolean b = value.trim().equalsIgnoreCase("true") || value.trim().equals("1");
                dos.writeByte(b ? 1 : 0);
                break;
            case TYPE_BIGDECIMAL:
                BigDecimal bd = new BigDecimal(value.trim());
                dos.writeInt(bd.scale());
                dos.writeLong(bd.unscaledValue().longValueExact());
                dos.writeLong(0L);
                break;
            default:
                throw new IllegalStateException("Unknown numerical tag: " + tag);
        }
        dos.flush();
        return baos.toByteArray();
    }

    private String decode(int tag, DataInputStream dis) throws IOException {
        switch (tag) {
            case TYPE_BYTE:       return String.valueOf(dis.readByte());
            case TYPE_SHORT:      return String.valueOf(dis.readShort());
            case TYPE_INT:        return String.valueOf(dis.readInt());
            case TYPE_LONG:       return String.valueOf(dis.readLong());
            case TYPE_FLOAT:      return String.valueOf(dis.readFloat());
            case TYPE_DOUBLE:     return String.valueOf(dis.readDouble());
            case TYPE_BOOLEAN:    return dis.readByte() == 1 ? "true" : "false";
            case TYPE_BIGDECIMAL:
                int scale = dis.readInt();
                long unscaled = dis.readLong();
                dis.readLong();
                return new BigDecimal(BigInteger.valueOf(unscaled), scale).toPlainString();
            default:
                throw new IllegalStateException("Unknown numerical tag: " + tag);
        }
    }
}
