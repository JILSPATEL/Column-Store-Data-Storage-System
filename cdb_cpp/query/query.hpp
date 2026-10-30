#pragma once

#include "../cdb.hpp"

namespace cdb::query {
struct WhereCondition : cdb::Cond {
  WhereCondition(std::string column, std::string op, std::string value) {
    col = std::move(column);
    this->op = std::move(op);
    val = std::move(value);
  }
};

class WhereClause : public cdb::Where {
public:
  explicit WhereClause(std::string expression) {
    this->expression = std::move(expression);
  }
};

class QueryEngine {
  cdb::Database database;

public:
  explicit QueryEngine(const cdb::fs::path &root) : database(root) {}
  std::string execute(const std::string &query) {
    return database.execute(query);
  }
  std::string dumpIndex(const std::string &table) const {
    return database.dump(table);
  }
};
}
