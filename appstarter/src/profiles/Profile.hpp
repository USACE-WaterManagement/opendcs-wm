#pragma once
#include <filesystem>
#include <string>

namespace cwms {
    namespace opendcs {

using std::filesystem::path;
using std::string;

class Profile {
    private:
        path file;

    public:

        const path& get_profile_path();

};

    }
}