#include "environment_vars.hpp"
#include <cstdlib>
#include <algorithm>
#include <vector>
#include <sstream>
#include <string>
#include <iostream>

namespace cwms {
    namespace opendcs {

namespace fs = std::filesystem;

environment_vars::environment_vars(std::string type, std::string database_url, std::string database_auth,
                                   std::string database_driver, std::vector<std::string> offices, std::string datatype_standard,
                                   std::string key_generator, std::string application_name, std::filesystem::path install_dir,
                                   std::filesystem::path user_dir)
                : type(type), database_url(database_url), database_auth(database_auth),
                  database_driver(database_driver), offices(offices), datatype_standard(datatype_standard),
                  key_generator(key_generator), application_name(application_name), install_dir(install_dir),
                  user_dir(user_dir) {
}


std::string getenv(const std::string& var_name) {
    const char *ret = std::getenv(var_name.c_str());
    if (ret) {
        return ret;
    } else {
        return "";
    }
}

std::vector<std::string> build_office_list(const std::string& office_str) {
    std::vector<std::string> offices;
    std::stringstream text(office_str);
    std::string next_office;
    while (std::getline(text, next_office, ',')) {
        offices.push_back(next_office);
    }
    return offices;
}

environment_vars environment_vars::build() {
    fs::path install_dir = [](){
        auto dir = getenv(DCSTOOL_HOME);
        if (dir == "") {
            throw std::runtime_error(DCSTOOL_HOME + " Directory is not set.");
        }
        return fs::path(dir).lexically_normal();
    }();
    if (!fs::exists(install_dir)) {
        throw std::runtime_error(DCSTOOL_HOME + " directory " + install_dir.string() + " doesn't exist.");
    }

    fs::path user_dir = [](){ 
        auto dir = getenv(USERDIR);
        if (dir == "") {
            throw std::runtime_error(USERDIR + " Directory is not set.");
        }
        return fs::path(dir).lexically_normal();
    }();
    if (!fs::exists(user_dir)) {
        throw std::runtime_error(USERDIR + " directory " + user_dir.string() + " doesn't exist.");
    }
    
    std::string database_type = getenv(TYPE);
    std::string database_url = getenv(DATABASE_URL);
    std::string database_auth = getenv(DATABASE_AUTH);
    std::string database_driver = getenv(DATABASE_DRIVER);
    std::string office_str = getenv(OFFICE);
    std::string datatype_standard = getenv(DATATYPE_STANDARD);
    std::string key_generator = getenv(KEY_GENERATOR);
    std::string application_name = getenv(APPLICATION_NAME);


    std::vector<std::string> offices;
    return {database_type, database_url, database_auth,
            database_driver, build_office_list(office_str), datatype_standard,
            key_generator, application_name, install_dir, user_dir};
}

    }
}