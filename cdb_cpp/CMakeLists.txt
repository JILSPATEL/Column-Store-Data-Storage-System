cmake_minimum_required(VERSION 3.16)
project(cdb_cpp LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_executable(cdb_cli main.cpp)
target_include_directories(cdb_cli PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_sources(cdb_cli PRIVATE
  api/database_api.hpp
  client/cli.hpp
  ddl/schema.hpp
  query/query.hpp
  query/querytypes/query_types.hpp
  storage/storage.hpp
  storage/persistence/column_persister.hpp
  util/filesystem.hpp
)
if(MSVC)
  target_compile_options(cdb_cli PRIVATE /W4)
else()
  target_compile_options(cdb_cli PRIVATE -Wall -Wextra -pedantic)
endif()
