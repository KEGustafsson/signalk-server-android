add_executable(nodeexec main.cc)
# Node 24's headers (V8's in particular) are C++20.
target_compile_features(nodeexec PRIVATE cxx_std_20)
target_compile_options(nodeexec PRIVATE -Wall -Wextra -Werror)
target_link_libraries(nodeexec PRIVATE node)
# libnode.so and libc++_shared.so sit beside the launcher in nativeLibraryDir. NodeRuntime also
# sets LD_LIBRARY_PATH, but a process that clears its environment must still find them.
# A link option because CMake's Android platform has no RPATH flag, so the RPATH properties
# would be ignored without a word. CMake escapes the `$` for Ninja and the shell; 3.22, AGP's
# default, gets that wrong and leaves a bare `$` (hence the pinned CMake in node-runtime).
target_link_options(nodeexec PRIVATE "-Wl,-rpath,$ORIGIN")
set_target_properties(nodeexec PROPERTIES OUTPUT_NAME "libnodeexec.so" SUFFIX "")
