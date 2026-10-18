// The `node` executable on Android: node::Start, in a process group of its own when asked.
//
// nodejs-mobile ships Node as libnode.so, a library an app normally starts on a thread through
// JNI. Signal K Server needs a real executable instead (docs/PLAN.md §1.1): npm runs lifecycle
// scripts with it, child_process.fork() and the server's restart re-exec process.execPath, and
// all of that only works when node is a file that can be exec'd. This is that file. It is
// packaged as lib/<abi>/libnodeexec.so so the installer extracts it to nativeLibraryDir, the one
// directory an app may execute from, and NodeRuntime starts it with a ProcessBuilder.
//
// The environment (HOME, TMPDIR, PATH, LD_LIBRARY_PATH, NODE_OPTIONS) is NodeRuntime's to set
// before exec; this file adds nothing to it, so a node started by node sees what its parent saw.
//
// One thing it does take away. NodeRuntime names a file in POCKETSK_GROUP_FILE for the node it
// starts itself, and only for that one: this node then leads a process group of its own and
// writes its pid, which is the group's id, to that file. Everything it starts (npm's lifecycle
// scripts, a plugin's helpers) stays in the group, so the app can signal all of it at once;
// java.lang.Process can only signal the one pid it forked, and Android gives an app no way to
// find that pid's children. The variable is removed before Node starts, so a node that a script
// runs joins its parent's group instead of leading one, and the group stays whole.

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>

#include "node.h"

namespace {

constexpr const char* kGroupFile = "POCKETSK_GROUP_FILE";

// Leads a new process group and records it, when NodeRuntime asked for that. The file is written
// whole and renamed into place, so a reader sees a complete id or nothing; it is written only
// once the group exists, so its presence means exactly that. Nothing has been started yet at
// this point, so a reader that finds no file has nothing but this process to signal. A failure
// is reported on stderr, which is the server's log, and Node still starts: a process that can
// only be stopped by itself is better than none.
void LeadGroup() {
  const char* path = getenv(kGroupFile);
  if (path == nullptr) return;
  const std::string target(path);
  unsetenv(kGroupFile);
  if (setpgid(0, 0) != 0) {
    fprintf(stderr, "pocketsk launcher: setpgid: %s\n", strerror(errno));
    return;
  }
  const std::string partial = target + ".part";
  FILE* f = fopen(partial.c_str(), "w");
  if (f == nullptr) {
    fprintf(stderr, "pocketsk launcher: %s: %s\n", partial.c_str(), strerror(errno));
    return;
  }
  const bool written = fprintf(f, "%d\n", static_cast<int>(getpid())) > 0;
  if (fclose(f) != 0 || !written || rename(partial.c_str(), target.c_str()) != 0) {
    fprintf(stderr, "pocketsk launcher: %s: %s\n", target.c_str(), strerror(errno));
    unlink(partial.c_str());
  }
}

}  // namespace

int main(int argc, char** argv) {
  LeadGroup();
  return node::Start(argc, argv);
}
