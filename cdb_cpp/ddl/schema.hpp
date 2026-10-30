#pragma once

#include "../cdb.hpp"

namespace cdb::ddl {
class ColumnSchema : public cdb::Column {
public:
  ColumnSchema() = default;
  ColumnSchema(std::string columnName, std::string columnType) {
    name = std::move(columnName);
    type = std::move(columnType);
  }
};

class TableSchema : public cdb::Schema {
public:
  explicit TableSchema(std::string tableName) {
    name = std::move(tableName);
  }
};

class SchemaManager {
  cdb::SchemaManager manager;

public:
  explicit SchemaManager(const cdb::fs::path &root) : manager(root) {}
  cdb::Schema *get(const std::string &name) { return manager.get(name); }
  const std::map<std::string, cdb::Schema> &all() const {
    return manager.all();
  }
  void create(const cdb::Schema &schema) { manager.create(schema); }
  void drop(const std::string &name) { manager.drop(name); }
};
}
