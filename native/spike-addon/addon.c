// Spike R2 (docs/PLAN.md §3): a hello-world N-API addon, built the way plugin prebuilds are.
//
// The question R2 asks is whether a `.node` file in the app's writable storage can be loaded at
// all: Android refuses to exec() a file there, and a native addon is mapped executable by
// dlopen(). Every native addon a Signal K plugin brings (serialport, sqlite) arrives that way,
// fetched by npm into node_modules, so the answer decides PLAN §9.
//
// CMakeLists.txt builds it twice, once linked the way generic prebuilds are and once the way
// nodejs-mobile links its own, because which of the two loads is the other half of R2. The app
// copies both out of nativeLibraryDir into files/ and requires them from there (MainActivity).

#include <node_api.h>

static napi_value hello(napi_env env, napi_callback_info info) {
  (void) info;
  napi_value result;
  if (napi_create_string_utf8(env, "loaded from writable storage", NAPI_AUTO_LENGTH, &result) != napi_ok) return NULL;
  return result;
}

NAPI_MODULE_INIT() {
  napi_value fn;
  if (napi_create_function(env, "hello", NAPI_AUTO_LENGTH, hello, NULL, &fn) != napi_ok) return NULL;
  if (napi_set_named_property(env, exports, "hello", fn) != napi_ok) return NULL;
  return exports;
}
