#pragma once
#include <string>
#include <vector>
#include <filesystem>
#include <cstdlib>

namespace cwms {
    namespace opendcs {

const std::string USERDIR = "DCSTOOL_USERDIR";
const std::string TYPE = "DATABASE_TYPE";
const std::string DATABASE_URL = "DATABASE_URL";
const std::string DATABASE_AUTH = "DB_AUTH";
const std::string DATABASE_DRIVER = "DATABASE_DRIVER";
const std::string OFFICE = "CWMS_OFFICE";
const std::string DATATYPE_STANDARD = "DATATYPE_STANDARD";
const std::string KEY_GENERATOR = "KEYGENERATOR";
const std::string APPLICATION_NAME = "APPLICATION_NAME";
const std::string DCSTOOL_HOME = "DCSTOOL_HOME";


struct environment_vars {
    const std::string type;
    const std::string database_url;
    const std::string database_auth;
    const std::string database_driver;
    const std::vector<std::string> offices;
    const std::string datatype_standard;
    const std::string key_generator;
    const std::string application_name;
    const std::filesystem::path install_dir;
    const std::filesystem::path user_dir;


    environment_vars() = delete;    
    environment_vars(const environment_vars& other) = default;
    environment_vars(environment_vars&& other) = default;
    environment_vars& operator=(const environment_vars& other) = default;
    environment_vars& operator=(environment_vars&& other) = default;
    ~environment_vars() = default;

    static environment_vars build();
    private:
        environment_vars(std::string type, std::string database_url, std::string database_auth, std::string database_driver,
                         std::vector<std::string> offices, std::string datatype_standard, std::string key_generator,
                         std::string application_name, std::filesystem::path install_dir, std::filesystem::path user_dir);
};

    }
}