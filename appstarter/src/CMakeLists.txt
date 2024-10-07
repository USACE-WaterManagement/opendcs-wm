file(GLOB_RECURSE CXX_SRC *.cpp *.hpp)
add_executable(appstarter ${CXX_SRC})
target_link_libraries(appstarter PUBLIC reproc++ optionparser)
target_compile_options(appstarter PUBLIC -rdynamic)
