// The `node` executable on Android: nothing but node::Start.
//
// nodejs-mobile ships Node as libnode.so, a library an app normally starts on a thread through
// JNI. Signal K Server needs a real executable instead (docs/PLAN.md §1.1): npm runs lifecycle
// scripts with it, child_process.fork() and the server's restart re-exec process.execPath, and
// all of that only works when node is a file that can be exec'd. This is that file. It is
// packaged as lib/<abi>/libnodeexec.so so the installer extracts it to nativeLibraryDir, the one
// directory an app may execute from, and NodeRuntime starts it with a ProcessBuilder.
//
// The environment (HOME, TMPDIR, PATH, LD_LIBRARY_PATH, NODE_OPTIONS) is NodeRuntime's to set
// before exec; this file adds nothing, so a node started by node sees what its parent saw.

#include "node.h"

int main(int argc, char** argv) {
  return node::Start(argc, argv);
}
