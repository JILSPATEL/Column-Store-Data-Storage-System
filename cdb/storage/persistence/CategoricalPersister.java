package cdb.storage.persistence;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class CategoricalPersister extends BasePersister {

    public static final int TYPE_STRING = 9;
    private final String dictPath;

    public CategoricalPersister(String path) {
        super(path);
        this.dictPath = path.replace(".bin", ".dict");
    }

    @Override
    protected int valueWidth(int tag) {
        // We store the dictionary ID as a 4-byte integer in the .bin file
        return 4;
    }

    @Override
    public void create(int tag) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(path))) {
            dos.writeInt(tag);
            dos.writeInt(0);
        }
        // Create an empty dictionary
        saveDictionary(new ArrayList<>());
    }

    @Override
    public void append(String value) throws IOException {
        List<String> dictionary = loadDictionary();
        int id = dictionary.indexOf(value);
        if (id == -1) {
            id = dictionary.size();
            dictionary.add(value);
            saveDictionary(dictionary);
        }

        try (FileOutputStream fos = new FileOutputStream(path, true)) {
            fos.write(ALIVE);
            DataOutputStream dos = new DataOutputStream(fos);
            dos.writeInt(id);
        }
        incrementRecordCount();
    }

    @Override
    public List<String> readAll() throws IOException {
        List<String> dictionary = loadDictionary();
        List<String> results = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            dis.readInt(); // tag
            int total = dis.readInt();
            int width = valueWidth(TYPE_STRING);

            for (int i = 0; i < total; i++) {
                byte flag = dis.readByte();
                if (flag == DELETED) {
                    dis.skipBytes(width);
                } else {
                    int id = dis.readInt();
                    results.add(id < dictionary.size() ? dictionary.get(id) : "null");
                }
            }
        }
        return results;
    }

    @Override
    public void update(int rowIndex, String value) throws IOException {
        int physIdx = findPhysicalIndex(rowIndex);
        if (physIdx < 0) return;

        List<String> dictionary = loadDictionary();
        int id = dictionary.indexOf(value);
        if (id == -1) {
            id = dictionary.size();
            dictionary.add(value);
            saveDictionary(dictionary);
        }

        int tag = getTag();
        long offset = recordOffset(tag, physIdx) + 1;
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(offset);
            raf.writeInt(id);
        }
    }

    private List<String> loadDictionary() throws IOException {
        List<String> dict = new ArrayList<>();
        File file = new File(dictPath);
        if (!file.exists()) return dict;

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            if (file.length() < 4) return dict;
            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                dict.add(dis.readUTF());
            }
        }
        return dict;
    }

    private void saveDictionary(List<String> dict) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(dictPath))) {
            dos.writeInt(dict.size());
            for (String s : dict) {
                dos.writeUTF(s);
            }
        }
    }
}
