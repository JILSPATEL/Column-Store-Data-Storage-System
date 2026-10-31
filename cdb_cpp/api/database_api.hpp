#pragma once

#include "../cdb.hpp"

namespace cdb::api {
class DatabaseAPI {
  cdb::Database database;

public:
  explicit DatabaseAPI(const cdb::fs::path &root) : database(root) {}

  // Exposes storage-engine injection through the higher-level database API.
  DatabaseAPI(const cdb::fs::path &root,
              std::unique_ptr<cdb::StorageEngine> storage)
      : database(root, std::move(storage)) {}

  std::string execute(const std::string &query) {
    return database.execute(query);
  }

  std::string dumpIndex(const std::string &table) const {
    return database.dump(table);
  }

  const cdb::SchemaManager &schemas() const {
    return database.schemas();
  }
};
}
