#pragma once

#include "../query.hpp"

namespace cdb::query::types {
struct Query {
  virtual ~Query() = default;
};

struct SelectQuery : Query {
  std::string table;
  std::vector<std::string> columns;
  std::string where;
};

struct InsertQuery : Query {
  std::string table;
  std::vector<std::string> values;
};

struct UpdateQuery : Query {
  std::string table;
  std::string column;
  std::string value;
  std::string where;
};

struct DeleteQuery : Query {
  std::string table;
  std::string where;
};
}
