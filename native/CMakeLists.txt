# The native half of node-runtime (docs/PLAN.md §4.1), built by the node-runtime Gradle module.
#
# libnode.so is nodejs-mobile's, unpacked by Gradle into NODEJS_MOBILE_DIR; it is linked here as an
# imported library, and the Android Gradle plugin packages it into lib/<abi>/ for that reason.
# The launcher is this project's: an executable named like a library, so the build packages it
# in lib/<abi>/ too and the installer extracts it to nativeLibraryDir, where it may be exec'd.
cmake_minimum_required(VERSION 3.31)
project(pocketsk_node_runtime CXX)

if(NOT NODEJS_MOBILE_DIR)
  message(FATAL_ERROR "NODEJS_MOBILE_DIR is not set; build through the node-runtime Gradle module")
endif()

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
  IMPORTED_LOCATION "${NODEJS_MOBILE_DIR}/bin/${ANDROID_ABI}/libnode.so"
  INTERFACE_INCLUDE_DIRECTORIES "${NODEJS_MOBILE_DIR}/include/node")

add_subdirectory(launcher)
