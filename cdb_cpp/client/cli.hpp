#pragma once

#include "../api/database_api.hpp"

namespace cdb::client
{
  class CLIClient
  {
    cdb::fs::path root;
    std::unique_ptr<cdb::api::DatabaseAPI> api;
    std::string databaseName;
    std::vector<std::string> history;

  public:
    explicit CLIClient(cdb::fs::path databaseRoot)
        : root(std::move(databaseRoot)) {}

    void run(std::istream &input, std::ostream &output)
    {
      output << "Column-Store DB (C++17). Type HELP for help.\n";
      std::string query;
      while (output << "CDB"
                    << (databaseName.empty() ? "" : " [" + databaseName + "]")
                    << " > ",
             std::getline(input, query))
      {
        query = cdb::trim(query);
        if (query.empty())
          continue;
        if (!query.empty() && query.back() == ';')
          query = cdb::trim(query.substr(0, query.size() - 1));
        const auto command = cdb::upper(query);
        if (command == "EXIT" || command == "QUIT")
          break;
        if (command == "HISTORY")
        {
          for (size_t i = 0; i < history.size(); ++i)
            output << i + 1 << " " << history[i] << "\n";
          continue;
        }
        if (command == "CLEAR")
        {
          for (int i = 0; i < 40; ++i)
            output << "\n";
          continue;
        }
        if (query.size() > 1 && query.front() == '!' &&
            std::all_of(query.begin() + 1, query.end(),
                        [](char c)
                        { return std::isdigit((unsigned char)c); }))
        {
          const auto number = std::stoul(query.substr(1));
          if (number == 0 || number > history.size())
          {
            output << "Error: history entry not found.\n";
            continue;
          }
          query = history[number - 1];
          output << query << "\n";
        }
        else
        {
          history.push_back(query);
        }
        if (command == "HELP")
        {
          output << "CREATE DATABASE n | USE DATABASE n | DROP DATABASE n | "
                    "SHOW DATABASES | SHOW TABLES | SHOW BITMAP INDEX t | "
                    "HISTORY | !n | CLEAR | SQL CRUD | EXIT\n";
          continue;
        }
        if (command == "SHOW DATABASES")
        {
          for (auto &entry : cdb::fs::directory_iterator(root))
            if (entry.is_directory())
              output << entry.path().filename() << "\n";
          continue;
        }
        if (command.rfind("CREATE DATABASE ", 0) == 0)
        {
          cdb::fs::create_directories(root / cdb::trim(query.substr(16)));
          output << "Database created.\n";
          continue;
        }
        if (command.rfind("DROP DATABASE ", 0) == 0)
        {
          const auto name = cdb::trim(query.substr(14));
          if (name.empty())
          {
            output << "Error: database name is required.\n";
            continue;
          }
          std::error_code error;
          api.reset();
          if (name == databaseName)
            databaseName.clear();
          cdb::fs::remove_all(root / name, error);
          output << (error ? "Error: database not found.\n"
                           : "Database dropped.\n");
          continue;
        }
        if (command.rfind("USE DATABASE ", 0) == 0)
        {
          const auto name = cdb::trim(query.substr(13));
          const auto path = root / name;
          if (!cdb::fs::exists(path))
          {
            output << "Error: database not found.\n";
            continue;
          }
          databaseName = name;
          api.reset(new cdb::api::DatabaseAPI(path));
          output << "Switched to " << databaseName << ".\n";
          continue;
        }
        if (command == "SHOW TABLES")
        {
          if (!api)
          {
            output << "No database selected.\n";
            continue;
          }
          for (const auto &entry : api->schemas().all())
            output << entry.first << "\n";
          continue;
        }
        if (command.rfind("SHOW BITMAP INDEX ", 0) == 0)
        {
          if (api)
            output << api->dumpIndex(cdb::trim(query.substr(18))) << "\n";
          continue;
        }
        if (!api)
        {
          output << "No database selected.\n";
          continue;
        }
        output << api->execute(query) << "\n";
      }
    }
  };
} // namespace cdb::client
