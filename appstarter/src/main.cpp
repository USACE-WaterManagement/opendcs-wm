#include <iostream>
#include "options/environment_vars.hpp"

using namespace cwms::opendcs;

int main(int argc, char* argv[])
{
    environment_vars vars = environment_vars::build();
    std::cout << "Using APP " << vars.application_name << std::endl;
    return 0;
}