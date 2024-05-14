#include "TsDbApp.hpp"
#include <ranges>
#include <vector>
#include <iostream>
#include <algorithm>

namespace cwms {
    namespace opendcs {

namespace fs = std::filesystem;

TsDbApp::TsDbApp(fs::path install_dir, Profile profile, std::string app_name,
                 std::string app_class, std::string class_path,
                 std::map<std::string,std::string> properties)
        : install_dir(install_dir), profile(profile), app_name(app_name),
          app_class(app_class), class_path(class_path), properties(properties) {
}

int TsDbApp::run() {
    std::vector<std::string> arguments;
    reproc::options options;
    options.redirect.parent = true;

    arguments.push_back("java");
    std::transform(properties.begin(), properties.end(), std::back_inserter(arguments), [](const auto& pair){
        const auto& key = pair.first;
        const auto& value = pair.second;
        if (value.empty())
        {
            return key;
        }
        else
        {
            return key + "=" + value;
        }
    });
    arguments.push_back("-cp"), arguments.push_back(class_path);
    arguments.push_back(app_class);
    arguments.push_back("-P"); arguments.push_back(this->profile.get_profile_path().string());
    arguments.push_back("-d3");
    arguments.push_back("-l"); arguments.push_back("/dev/stdout");

    auto ret = process.start(arguments, options);
    if (ret) {
        throw std::runtime_error("Unable to start process " + ret.message());
    }
    return process.wait(reproc::infinite).first;
}

void TsDbApp::start() {
    this->future = std::async(std::launch::async, [app = this->shared_from_this()](){return app->run();});
}

std::shared_future<int> TsDbApp::get_future() {
    return this->future;
}

std::vector<fs::path> get_jars(const fs::path& dir) {
    std::vector<fs::path> jars;
    for(const auto& entry: fs::directory_iterator(dir)) {
        if (entry.is_regular_file() && entry.path().extension() == ".jar") {
            jars.push_back(entry.path());
        }
    }
    return jars;
}

std::string create_classpath(const std::filesystem::path& install_dir, const std::filesystem::path& user_dir) {
    std::vector<std::filesystem::path> jars;
    jars.push_back( (install_dir/"bin").append("opendcs.jar").lexically_normal().string());
    auto main_deps = install_dir / "dep";
    auto user_deps = user_dir / "dep";

    auto main_jars = get_jars(main_deps);
    auto user_jars = get_jars(user_deps);
    jars.reserve(main_jars.size() + user_jars.size());
    #ifdef __cpp_lib_containers_range
        jars.append_range(main_jars);
        jars.append_range(user_jars);
    #else
        jars.insert(jars.end(), main_jars.begin(), main_jars.end());
        jars.insert(jars.end(), user_jars.begin(), user_jars.end());
    #endif

    std::stringstream cp;
    for (auto path = jars.begin(); path != jars.end(); path++)
    {
        cp << path->string();
        if ((path+1) != jars.end())
        {
            cp << ":";
        }
    }
    return cp.str();
}


std::shared_ptr<TsDbApp> TsDbApp::create(Profile profile, const environment_vars& vars) {
    auto install_dir = vars.install_dir;
    auto user_dir = vars.user_dir;
    auto app_name = vars.application_name;
    auto app_class = "decodes.tsdb.ComputationApp";
    std::map<std::string,std::string> properties;
    properties["-Xmx256m"] = "";
    properties["-DDCSTOOL_HOME"] = install_dir.string();
    properties["-DDCSTOOL_USERDIR"] = user_dir.string();
    properties["-DDECODES_INSTALL_DIR"] = install_dir.string();
    auto class_path = create_classpath(install_dir, user_dir);
    auto app = std::make_shared<TsDbApp>(install_dir, profile, app_name, app_class, class_path, properties);
    app->start();

    return app;
}
    
    }
}