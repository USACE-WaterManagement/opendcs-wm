#pragma once
#include <filesystem>
#include <string>
#include <vector>
#include "../options/environment_vars.hpp"

namespace cwms {
    namespace opendcs {



class Profile {
    private:
        std::filesystem::path file;
        const std::string app_name;
        const std::string office;

        Profile(std::filesystem::path profile_file, std::string app_name);
    public:
        Profile() = delete;
        ~Profile() = default;
        Profile(const Profile& other) = default;
        Profile(Profile&& other) = default;
        Profile& operator= (const Profile& other) = delete;
        Profile& operator= (Profile &&other) = delete;

        const std::filesystem::path& get_profile_path() const;
        const std::string get_office() const;
        const std::string get_app_name() const;

        static std::vector<Profile> from(const environment_vars& vars, const std::filesystem::path& template_file);
};

    }
}