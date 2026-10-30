#pragma once

#include "../cdb.hpp"

namespace cdb::storage {
class StorageEngine {
protected:
  cdb::Storage storage;

public:
  explicit StorageEngine(const cdb::fs::path &root) : storage(root) {}
  virtual ~StorageEngine() = default;
  std::vector<std::string> readColumn(const std::string &table,
                                       const std::string &column) const {
    return storage.read(table, column);
  }
};

class BinaryStorageEngine : public StorageEngine {
public:
  explicit BinaryStorageEngine(const cdb::fs::path &root) : StorageEngine(root) {}
};
}
