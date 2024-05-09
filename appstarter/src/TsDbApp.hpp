#pragma once
#include <filesystem>
#include <string>
#include <reproc++/reproc.hpp>
#include <map>
#include "options/environment_vars.hpp"

namespace cwms {
    namespace opendcs {

/**
 * Wrapper to hold a reproc::process instance used to execute the program
*/
class TsDbApp {
    private:
        /**
         *  Name on disk of profile to use to start the application. 
         */
        const std::filesystem::path profile;
        /**
         * HDB Loading Application ID to assign
        */
        const std::string app_name;

        /**
         * Java class to be executed.
        */
        const std::string app_class;
        const std::string classpath;
        const std::map<std::string,std::string> properties;


        TsDbApp(std::filesystem::path profile, std::string app_name, std::string app_class);
    public:
        
        int run();

        static TsDbApp create(std::filesystem::path profile, const environment_vars& vars);
};

    }
}