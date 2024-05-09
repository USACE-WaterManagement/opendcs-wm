include(FetchContent)

FetchContent_Declare(
  reproc
  GIT_REPOSITORY https://github.com/HydrologicEngineeringCenter/reproc.git
  GIT_TAG        v14.2.5
)

# CMake 3.14+
set(REPROC++ ON)
set(REPROC_OBJECT_LIBRARIES ON)
FetchContent_MakeAvailable(reproc)