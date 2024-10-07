#include <iostream>
#include <future>
#include <chrono>
#include <thread>
#include <random>
#include <optionparser.hpp>
#include "options/environment_vars.hpp"
#include "profiles/Profile.hpp"
#include "TsDbApp.hpp"

using namespace cwms::opendcs;
using namespace std::chrono_literals;
const int RESULT_OK = 0;
const int RESULT_ERROR = 1;
enum optionIndex { UNKNOWN, CHECK, TEMPLATE };
const option::Descriptor usage[] = {
    {UNKNOWN, 0,"" , ""        , option::Arg::None, "USAGE: example [options]\n\n"
                                                    "Options:" },
    {CHECK,    0,"" , "check"  , option::Arg::None, "  --check  \tRun docker health check. exit 0 on healthy." },
    {TEMPLATE ,0,"t", ""       , option::Arg::Optional, "  -t   \tproperties template file to use."}, 
    {0,0,0,0,0,0}
};

int drive_opendcs(const std::string& template_path);
int health_check();

int main(int argc, char* argv[]) {

    argc-=(argc>0); argv+=(argc>0); // skip program name argv[0] if present
    option::Stats  stats(usage, argc, argv);
    option::Option options[stats.options_max], buffer[stats.buffer_max];
    option::Parser parse(usage, argc, argv, options, buffer);

    if (parse.error()) {
        return RESULT_ERROR;
    }
    if (options[CHECK]) {
        return health_check();
    } else if (options[TEMPLATE]) {
        std::cerr << "" << options[TEMPLATE].arg << std::endl;
        return drive_opendcs(options[TEMPLATE].arg);
    } else {
        std::cerr << "Must either ask for --check or provide a template with -t <template file>" << std::endl;
        return RESULT_ERROR;
    }
}

int health_check() {
    std::cerr << "Hello From Health Check." << std::endl;
    return RESULT_OK;
}

int drive_opendcs(const std::string& template_path) {
    try {
        environment_vars vars = environment_vars::build();
        std::cout << "Using APP " << vars.application_name << std::endl;
        std::filesystem::path property_template = std::filesystem::path(template_path).lexically_normal();
        std::cout << "Using template " << property_template << std::endl;
        std::vector<Profile> profiles = Profile::from(vars, template_path);
        std::cout << "Running for the following districts : " << std::endl;
        std::vector<std::shared_ptr<TsDbApp>> app_results;


        for (const auto& p: profiles) {
            std::cout << "\t" << p.get_profile_path().string() << std::endl;
            app_results.push_back(TsDbApp::create(p, vars));
        }

        while(true) {
            for (auto& app: app_results) {
                auto f = app->get_future();
                std::future_status status = f.wait_for(0ms);
                if (status == std::future_status::ready) {
                    int ret = static_cast<int>(f.get()); // intentionally narrowed
                    std::cerr << "App process has exited " << ret << std::endl;
                    return ret;
                }
            }
            std::cout << "Apps running" << std::endl;
            std::this_thread::sleep_for(std::chrono::seconds(2s));
        }
        return RESULT_OK;
    } catch (std::exception& ex) {
        std::cerr << "Application failure : " << ex.what() << std::endl;
        return RESULT_ERROR;
    }
}