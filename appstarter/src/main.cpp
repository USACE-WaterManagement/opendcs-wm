#include <iostream>
#include <future>
#include <chrono>
#include <thread>
#include <random>
#include "options/environment_vars.hpp"
#include "profiles/Profile.hpp"
#include "TsDbApp.hpp"

using namespace cwms::opendcs;
using namespace std::chrono_literals;

int main(int argc, char* argv[]) {
    try {
        environment_vars vars = environment_vars::build();
        std::cout << "Using APP " << vars.application_name << std::endl;
        std::filesystem::path property_template = std::filesystem::path(argv[1]).lexically_normal();
        std::cout << "Using template " << property_template << std::endl;
        std::vector<Profile> profiles = Profile::from(vars, argv[1]);
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
        return 0;
    } catch (std::exception& ex) {
        std::cerr << "Application failure : " << ex.what() << std::endl;
    }
}