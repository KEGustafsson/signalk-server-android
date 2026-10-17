# Two builds of one addon, because how prebuilds have to be linked is what R2 settles (PLAN §9):
#  - spike_napi: as prebuildify and node-gyp build for Linux. Links nothing from Node; its
#    napi_* symbols stay undefined until the node process that loads it supplies them. The NDK
#    links shared libraries with --no-undefined, which would refuse exactly that.
#  - spike_napi_linked: as nodejs-mobile's own addon test builds (tools/mobile-test/addon),
#    linked against libnode.so, so the dynamic linker has a library to find them in.
foreach(variant spike_napi spike_napi_linked)
  add_library(${variant} SHARED addon.c)
  target_include_directories(${variant} PRIVATE "${NODEJS_MOBILE_DIR}/include/node")
  target_compile_options(${variant} PRIVATE -Wall -Wextra -Werror)
endforeach()
target_link_options(spike_napi PRIVATE "-Wl,-z,undefs")
target_link_libraries(spike_napi_linked PRIVATE node)
