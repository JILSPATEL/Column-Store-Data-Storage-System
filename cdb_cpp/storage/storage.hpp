#pragma once

#include "../cdb.hpp"

namespace cdb::storage {
// Re-export the backend contract from the public storage namespace.
using StorageEngine = cdb::StorageEngine;

// Named persistent backend for clients that want explicit engine selection.
class PersistentStorageEngine : public cdb::PersistentStorage {
public:
  explicit PersistentStorageEngine(const cdb::fs::path &root)
      : cdb::PersistentStorage(root) {}
};

using BinaryStorageEngine = PersistentStorageEngine;
}
