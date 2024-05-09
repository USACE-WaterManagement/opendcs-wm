#include "TsDbApp.hpp"

namespace cwms {
    namespace opendcs {

TsDbApp::TsDbApp(std::filesystem::path profile, std::string app_name, std::string app_class) 
        : profile(profile), app_name(app_name), app_class(app_class) {

}

int TsDbApp::run() {
    std::vector<std::string> arguments;
    reproc::process process;
    reproc::options options;
    options.redirect.parent = true;
    auto ret = process.start(arguments, options);
    if (ret) {
        throw std::runtime_error("Unable to start process " + ret.message());
    }
    return process.wait(reproc::infinite).first;
}


TsDbApp create(std::filesystem::path profile, const environment_vars& vars) {
    // arguments
    // find java
    // setup classpath
    // java props
}
    
    }
}