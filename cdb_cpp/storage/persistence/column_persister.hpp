#pragma once

#include "../../cdb.hpp"

namespace cdb::storage::persistence {
class ColumnPersister {
public:
  virtual ~ColumnPersister() = default;
  virtual std::vector<std::string> read() const = 0;
};
}
