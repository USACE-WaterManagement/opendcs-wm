#pragma once
#include <filesystem>
#include <string>
#include <reproc++/reproc.hpp>
#include <map>
#include <future>
#include "options/environment_vars.hpp"
#include "profiles/Profile.hpp"

namespace cwms {
    namespace opendcs {

/**
 * Wrapper to hold a reproc::process instance used to execute the program
*/
class TsDbApp : public std::enable_shared_from_this<TsDbApp> {
    private:
        /**
         *  Name on disk of profile to use to start the application. 
         */
        const Profile profile;
        /**
         * Location of OpenDCS Installation
         */
        const std::filesystem::path install_dir;
        /**
         * HDB Loading Application ID to assign
        */
        const std::string app_name;

        /**
         * Java class to be executed.
        */
        const std::string app_class;
        const std::string class_path;
        const std::map<std::string,std::string> properties;
        /**
         * Access to the application return for the management system
        */
        std::shared_future<int> future;
        /**
         * Handle to the running process to allow various IO.
        */
        reproc::process process;

    public:
        TsDbApp(std::filesystem::path install_dir, Profile profile, std::string app_name,
                std::string app_class, std::string class_path,
                std::map<std::string,std::string> properties);
        TsDbApp() = delete;
        ~TsDbApp() = default;
        TsDbApp(const TsDbApp& other) = delete;
        TsDbApp& operator=(const TsDbApp& other) = delete;
        TsDbApp(TsDbApp&& other) = delete;
        TsDbApp& operator=(TsDbApp&& other) = delete;
        
        std::shared_future<int> get_future();
        int run();
        void start();

        static std::shared_ptr<TsDbApp> create(Profile profile, const environment_vars& vars);
};

    }
}