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
//
// It is also `npm`. Signal K Server runs npm by name: `npm --version` at start, and its App
// Store's installs as `npm install` in the config directory. On a Pi that is the shell wrapper
// npm ships; here no script in the app's data directory may be exec'd (W^X, docs/PLAN.md §4.2),
// so NodeRuntime links files/bin/npm to this executable beside files/bin/node, and a launcher
// started under the name `npm` runs node with the script that POCKETSK_NPM_CLI names in front of
// its arguments, as the wrapper would: runtime-tools/npm's npm-launch.js, which runs npm-cli.js
// and puts the native addons' Android builds in place after an install.

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

#include "node.h"

namespace {

constexpr const char* kGroupFile = "POCKETSK_GROUP_FILE";
constexpr const char* kNpmCli = "POCKETSK_NPM_CLI";

// Writes this process's pid to `target`, whole and renamed into place, so a reader sees a
// complete id or nothing. False, said on stderr, when it could not.
bool Record(const std::string& target) {
  const std::string partial = target + ".part";
  FILE* f = fopen(partial.c_str(), "w");
  if (f == nullptr) {
    fprintf(stderr, "pocketsk launcher: %s: %s\n", partial.c_str(), strerror(errno));
    return false;
  }
  const bool written = fprintf(f, "%d\n", static_cast<int>(getpid())) > 0;
  if (fclose(f) != 0 || !written || rename(partial.c_str(), target.c_str()) != 0) {
    fprintf(stderr, "pocketsk launcher: %s: %s\n", target.c_str(), strerror(errno));
    unlink(partial.c_str());
    return false;
  }
  return true;
}

// Leads a new process group and records it, when NodeRuntime asked for that. A record exists
// exactly when this process leads a group of its own, and nothing has been started yet when it
// is written, so a reader that finds none has nothing but this process to signal. That holds on
// every failure too: when the record cannot be written the group is given up again (this
// process rejoins its parent's, as a node started without the variable would be), since a group
// nobody can name would hide whatever is started in it. Failures are reported on stderr, which is
// the server's log, and Node still starts, stoppable as a single process. Only when the group can
// neither be recorded nor left is there no truthful state to start in; false then.
bool LeadGroup() {
  const char* path = getenv(kGroupFile);
  if (path == nullptr) return true;
  const std::string target(path);
  unsetenv(kGroupFile);
  const pid_t parentGroup = getpgrp();
  if (setpgid(0, 0) != 0) {
    fprintf(stderr, "pocketsk launcher: setpgid: %s\n", strerror(errno));
    return true;
  }
  if (Record(target)) return true;
  if (setpgid(0, parentGroup) == 0) return true;
  fprintf(stderr, "pocketsk launcher: rejoining group %d: %s\n", static_cast<int>(parentGroup),
          strerror(errno));
  return false;
}

// True when this process was started as `npm`: argv[0] as exec'd, by name from PATH or by the
// path of the files/bin/npm link, whose last component is the name either way.
bool StartedAsNpm(const char* argv0) {
  const char* slash = strrchr(argv0, '/');
  return strcmp(slash == nullptr ? argv0 : slash + 1, "npm") == 0;
}

// Node's arguments for `npm args...`: argv[0], npm-cli.js, then the rest. The strings are laid
// out end to end in `storage`, as the kernel lays out a process's own: libuv takes the room it
// may overwrite for process.title (which npm sets) to run from argv[0] to the end of the last
// argument, so separately allocated strings would let it write past them.
std::vector<char*> NpmArgs(int argc, char** argv, const char* cli, std::vector<char>& storage) {
  std::vector<const char*> parts{argv[0], cli};
  for (int i = 1; i < argc; i++) parts.push_back(argv[i]);
  size_t size = 0;
  for (const char* p : parts) size += strlen(p) + 1;
  storage.assign(size, '\0');
  std::vector<char*> args;
  char* at = storage.data();
  for (const char* p : parts) {
    const size_t n = strlen(p) + 1;
    memcpy(at, p, n);
    args.push_back(at);
    at += n;
  }
  args.push_back(nullptr);
  return args;
}

}  // namespace

int main(int argc, char** argv) {
  if (!LeadGroup()) return 1;
  if (argc > 0 && StartedAsNpm(argv[0])) {
    const char* cli = getenv(kNpmCli);
    if (cli == nullptr || *cli == '\0') {
      fprintf(stderr, "pocketsk launcher: npm: %s is not set\n", kNpmCli);
      return 127;
    }
    // Static: Node may keep pointers into its arguments for as long as it runs.
    static std::vector<char> storage;
    static std::vector<char*> args = NpmArgs(argc, argv, cli, storage);
    return node::Start(static_cast<int>(args.size()) - 1, args.data());
  }
  return node::Start(argc, argv);
}
