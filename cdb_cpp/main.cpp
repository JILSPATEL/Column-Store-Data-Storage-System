#include "api/database_api.hpp"
#include "client/cli.hpp"
#include <iostream>
int main(int argc, char** argv) {
    std::string root;
    if (argc > 1) {
        root = argv[1];
    } else if (cdb::fs::exists("databases/company_db")) {
        root = "databases";
    } else if (cdb::fs::exists("../databases/company_db")) {
        root = "../databases";
    } else {
        root = "databases";
    }
    cdb::fs::create_directories(root);
    cdb::client::CLIClient shell(root);
    shell.run(std::cin, std::cout);
}
