#pragma once
#include <filesystem>
#include <string>

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
        std::filesystem::path profile;
        /**
         * HDB Loading Application ID to assign
        */
        std::string app_name;

        /**
         * Java class to be executed.
        */
        std::string app_class;
    public:
        TsDbApp(std::filesystem::path profile, std::string app_name, std::string app_class);
        void run();
};

    }
}