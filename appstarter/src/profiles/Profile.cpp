#include "Profile.hpp"
#include <fstream>
#include <iostream>
#include <sstream>
#include <regex>
namespace cwms {
    namespace opendcs {

using std::filesystem::path;
using std::string;
using std::vector;
using std::ifstream;
using std::ofstream;
using std::stringstream;
using std::regex;
using std::regex_replace;

Profile::Profile(path profile_file, std::string app_name) : file(profile_file), app_name(app_name) {}

const path& Profile::get_profile_path() const {
    return this->file;
}

const std::string Profile::get_office() const {
    return this->office;
}

const std::string Profile::get_app_name() const {
    return this->app_name;
}

std::vector<Profile> Profile::from(const environment_vars& vars, const path& template_file) {
    std::vector<Profile> profiles;

    const std::string properties_template = [&template_file](){
        if (! std::filesystem::exists(template_file)) {
            throw std::runtime_error("File '" + template_file.string() + "' doesn't exist or is not accessible.");
        }
        ifstream file(template_file);
        if (!file.is_open()) {
            throw std::runtime_error("Failed to open properties template file.");
        }
        stringstream reader;
        reader << file.rdbuf();
        return reader.str();
    }();    

    for(const auto& office: vars.offices) {
        path profile_file = path(office).replace_extension(".profile").lexically_normal();
        ofstream file(profile_file);
        if (!file.is_open()) {
            throw std::runtime_error("Unable to open office profile file.");
        }
        std::string result = properties_template; // make copy to work on
        result = regex_replace(result, regex("OPENDCS_CWMS_OFFICE"), office);
        result = regex_replace(result, regex("OPENDCS_DATABASE_TYPE"), vars.type);
        result = regex_replace(result, regex("OPENDCS_DATABASE_URL"), vars.database_url);
        result = regex_replace(result, regex("OPENDCS_SITE_NAME_PREFERENCE"), vars.datatype_standard);
        result = regex_replace(result, regex("OPENDCS_DB_AUTH"), vars.database_auth);
        result = regex_replace(result, regex("OPENDCS_DATABASE_DRIVER"), vars.database_driver);
        result = regex_replace(result, regex("OPENDCS_DATATYPE_STANDARD"), vars.datatype_standard);
        result = regex_replace(result, regex("OPENDCS_KEYGENERATOR"), vars.key_generator);
        file << result;
        file.close();
        Profile p(profile_file, vars.application_name);
        profiles.push_back(p);
    }
    return profiles;

}
    }
}