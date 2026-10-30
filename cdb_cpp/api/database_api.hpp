#pragma once

#include "../cdb.hpp"

namespace cdb::api {
class DatabaseAPI {
  cdb::Database database;

public:
  explicit DatabaseAPI(const cdb::fs::path &root) : database(root) {}

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
