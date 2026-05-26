#define _GNU_SOURCE

#include <jni.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <setjmp.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <termios.h>
#include <unistd.h>

#include "zlib.h"

#define DEFAULT_LINUX_PATH "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
#define DEFAULT_TOOL_SHELL "PATH=" DEFAULT_LINUX_PATH "; export PATH; cd /wlantool && exec /bin/sh -i"

#define MODE_PROOT 1
#define MODE_CHROOT 2

struct tar_header {
  char name[100];
  char mode[8];
  char uid[8];
  char gid[8];
  char size[12];
  char mtime[12];
  char chksum[8];
  char typeflag;
  char linkname[100];
  char magic[6];
  char version[2];
  char uname[32];
  char gname[32];
  char devmajor[8];
  char devminor[8];
  char prefix[155];
  char pad[12];
};

struct error_ctx {
  jmp_buf env;
  char message[512];
};

static __thread struct error_ctx *g_error_ctx = NULL;

static void dief(const char *fmt, ...) {
  va_list ap;

  va_start(ap, fmt);
  if (g_error_ctx != NULL) {
    vsnprintf(g_error_ctx->message, sizeof(g_error_ctx->message), fmt, ap);
    va_end(ap);
    longjmp(g_error_ctx->env, 1);
  }
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
  exit(1);
}

static void die_errno(const char *what, const char *arg) {
  if (arg != NULL) {
    dief("%s: %s: %s", what, arg, strerror(errno));
  } else {
    dief("%s: %s", what, strerror(errno));
  }
}

static void *xmalloc(size_t size) {
  void *ptr = malloc(size);
  if (ptr == NULL) {
    dief("out of memory");
  }
  return ptr;
}

static void *xcalloc(size_t n, size_t size) {
  void *ptr = calloc(n, size);
  if (ptr == NULL) {
    dief("out of memory");
  }
  return ptr;
}

static char *xstrdup(const char *s) {
  char *copy = strdup(s);
  if (copy == NULL) {
    dief("out of memory");
  }
  return copy;
}

static char *xasprintf(const char *fmt, ...) {
  va_list ap;
  va_list copy;
  int needed;
  char *out;

  va_start(ap, fmt);
  va_copy(copy, ap);
  needed = vsnprintf(NULL, 0, fmt, copy);
  va_end(copy);
  if (needed < 0) {
    va_end(ap);
    dief("vsnprintf failed");
  }

  out = xmalloc((size_t)needed + 1);
  vsnprintf(out, (size_t)needed + 1, fmt, ap);
  va_end(ap);
  return out;
}

static bool is_all_zero(const unsigned char *buf, size_t size) {
  size_t i;
  for (i = 0; i < size; i++) {
    if (buf[i] != 0) {
      return false;
    }
  }
  return true;
}

static unsigned long long parse_octal(const char *buf, size_t size) {
  unsigned long long value = 0;
  size_t i = 0;

  while (i < size && (buf[i] == ' ' || buf[i] == '\0')) {
    i++;
  }
  for (; i < size; i++) {
    if (buf[i] == '\0' || buf[i] == ' ') {
      break;
    }
    if (buf[i] < '0' || buf[i] > '7') {
      break;
    }
    value = (value << 3) + (unsigned long long)(buf[i] - '0');
  }
  return value;
}

static char *path_join2(const char *left, const char *right) {
  size_t left_len = strlen(left);
  size_t right_len = strlen(right);
  bool need_slash = left_len > 0 && left[left_len - 1] != '/';
  char *out = xmalloc(left_len + right_len + (need_slash ? 2 : 1));

  memcpy(out, left, left_len);
  if (need_slash) {
    out[left_len++] = '/';
  }
  memcpy(out + left_len, right, right_len);
  out[left_len + right_len] = '\0';
  return out;
}

static int rmrf_path(const char *path) {
  struct stat st;
  DIR *dir;
  struct dirent *entry;

  if (lstat(path, &st) != 0) {
    if (errno == ENOENT) {
      return 0;
    }
    return -1;
  }

  if (!S_ISDIR(st.st_mode) || S_ISLNK(st.st_mode)) {
    return unlink(path);
  }

  dir = opendir(path);
  if (dir == NULL) {
    return -1;
  }

  while ((entry = readdir(dir)) != NULL) {
    char *child;
    if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
      continue;
    }
    child = path_join2(path, entry->d_name);
    if (rmrf_path(child) != 0) {
      int saved = errno;
      free(child);
      closedir(dir);
      errno = saved;
      return -1;
    }
    free(child);
  }

  if (closedir(dir) != 0) {
    return -1;
  }
  return rmdir(path);
}

static void ensure_parent_dirs(const char *path, mode_t mode) {
  char *copy = xstrdup(path);
  char *p = copy;

  if (copy[0] == '/') {
    p++;
  }
  for (; *p != '\0'; p++) {
    if (*p != '/') {
      continue;
    }
    *p = '\0';
    if (copy[0] != '\0' && mkdir(copy, mode) != 0 && errno != EEXIST) {
      die_errno("mkdir", copy);
    }
    *p = '/';
  }
  free(copy);
}

static void ensure_dir(const char *path, mode_t mode) {
  struct stat st;
  if (lstat(path, &st) == 0) {
    if (S_ISDIR(st.st_mode)) {
      return;
    }
    if (unlink(path) != 0) {
      die_errno("unlink", path);
    }
  } else if (errno != ENOENT) {
    die_errno("lstat", path);
  }
  ensure_parent_dirs(path, 0755);
  if (mkdir(path, mode) != 0 && errno != EEXIST) {
    die_errno("mkdir", path);
  }
}

static bool path_is_safe(const char *path) {
  const char *p = path;

  if (path == NULL || *path == '\0') {
    return false;
  }
  if (*p == '/') {
    return false;
  }
  while (*p != '\0') {
    const char *start;
    size_t len;

    while (*p == '/') {
      p++;
    }
    start = p;
    while (*p != '\0' && *p != '/') {
      p++;
    }
    len = (size_t)(p - start);
    if (len == 0) {
      continue;
    }
    if (len == 1 && start[0] == '.') {
      continue;
    }
    if (len == 2 && start[0] == '.' && start[1] == '.') {
      return false;
    }
  }
  return true;
}

static char *join_under_root(const char *root, const char *rel) {
  if (strcmp(rel, ".") == 0 || strcmp(rel, "./") == 0) {
    return xstrdup(root);
  }
  if (!path_is_safe(rel)) {
    dief("unsafe archive path: %s", rel);
  }
  while (rel[0] == '.' && rel[1] == '/') {
    rel += 2;
  }
  return path_join2(root, rel);
}

static void gz_read_or_die(gzFile gz, void *buf, unsigned len) {
  unsigned char *p = buf;
  unsigned remaining = len;

  while (remaining > 0) {
    int rv = gzread(gz, p, remaining);
    if (rv <= 0) {
      dief("unexpected end of gzip stream");
    }
    p += rv;
    remaining -= (unsigned)rv;
  }
}

static void gz_skip_or_die(gzFile gz, unsigned long long len) {
  unsigned char buf[8192];
  while (len > 0) {
    unsigned chunk = len > sizeof(buf) ? sizeof(buf) : (unsigned)len;
    gz_read_or_die(gz, buf, chunk);
    len -= chunk;
  }
}

static void gz_skip_padding(gzFile gz, unsigned long long size) {
  unsigned long long padding = (512 - (size % 512)) % 512;
  if (padding != 0) {
    gz_skip_or_die(gz, padding);
  }
}

static char *tar_name_from_header(const struct tar_header *hdr) {
  size_t name_len = strnlen(hdr->name, sizeof(hdr->name));
  size_t prefix_len = strnlen(hdr->prefix, sizeof(hdr->prefix));
  char *name;

  if (prefix_len == 0) {
    name = xmalloc(name_len + 1);
    memcpy(name, hdr->name, name_len);
    name[name_len] = '\0';
    return name;
  }

  name = xmalloc(prefix_len + 1 + name_len + 1);
  memcpy(name, hdr->prefix, prefix_len);
  name[prefix_len] = '/';
  memcpy(name + prefix_len + 1, hdr->name, name_len);
  name[prefix_len + 1 + name_len] = '\0';
  return name;
}

static char *read_long_string(gzFile gz, unsigned long long size) {
  char *buf = xcalloc((size_t)size + 1, 1);
  gz_read_or_die(gz, buf, (unsigned)size);
  gz_skip_padding(gz, size);
  buf[size] = '\0';
  while (size > 0 && (buf[size - 1] == '\0' || buf[size - 1] == '\n')) {
    buf[size - 1] = '\0';
    size--;
  }
  return buf;
}

static void write_file_from_gz(gzFile gz, const char *path, mode_t mode,
                               unsigned long long size) {
  int fd;
  unsigned char buf[32768];

  ensure_parent_dirs(path, 0755);
  fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, mode ? mode : 0644);
  if (fd < 0) {
    die_errno("open", path);
  }

  while (size > 0) {
    unsigned chunk = size > sizeof(buf) ? sizeof(buf) : (unsigned)size;
    ssize_t written_total = 0;
    gz_read_or_die(gz, buf, chunk);
    while (written_total < (ssize_t)chunk) {
      ssize_t written = write(fd, buf + written_total, chunk - (unsigned)written_total);
      if (written < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        die_errno("write", path);
      }
      written_total += written;
    }
    size -= chunk;
  }

  if (fchmod(fd, mode ? mode : 0644) != 0) {
    int saved = errno;
    close(fd);
    errno = saved;
    die_errno("fchmod", path);
  }
  if (close(fd) != 0) {
    die_errno("close", path);
  }
}

static int cmd_extract(const char *archive, const char *dest) {
  gzFile gz;
  char *pending_long_name = NULL;
  char *pending_long_link = NULL;

  ensure_dir(dest, 0755);
  gz = gzopen(archive, "rb");
  if (gz == NULL) {
    dief("cannot open gzip archive: %s", archive);
  }

  for (;;) {
    struct tar_header hdr;
    unsigned long long size;
    mode_t mode;
    char typeflag;
    char *name = NULL;
    char *linkname = NULL;
    char *out_path = NULL;

    gz_read_or_die(gz, &hdr, sizeof(hdr));
    if (is_all_zero((const unsigned char *)&hdr, sizeof(hdr))) {
      break;
    }

    size = parse_octal(hdr.size, sizeof(hdr.size));
    mode = (mode_t)parse_octal(hdr.mode, sizeof(hdr.mode));
    typeflag = hdr.typeflag == '\0' ? '0' : hdr.typeflag;

    if (typeflag == 'L') {
      free(pending_long_name);
      pending_long_name = read_long_string(gz, size);
      continue;
    }
    if (typeflag == 'K') {
      free(pending_long_link);
      pending_long_link = read_long_string(gz, size);
      continue;
    }
    if (typeflag == 'x' || typeflag == 'g') {
      gz_skip_or_die(gz, size);
      gz_skip_padding(gz, size);
      continue;
    }

    if (pending_long_name != NULL) {
      name = pending_long_name;
      pending_long_name = NULL;
    } else {
      name = tar_name_from_header(&hdr);
    }

    if (pending_long_link != NULL) {
      linkname = pending_long_link;
      pending_long_link = NULL;
    } else {
      linkname = xstrdup(hdr.linkname);
    }

    out_path = join_under_root(dest, name);

    switch (typeflag) {
      case '5':
        ensure_dir(out_path, mode ? mode : 0755);
        break;
      case '2':
        ensure_parent_dirs(out_path, 0755);
        unlink(out_path);
        if (symlink(linkname, out_path) != 0) {
          die_errno("symlink", out_path);
        }
        break;
      case '1': {
        char *target = join_under_root(dest, linkname);
        ensure_parent_dirs(out_path, 0755);
        unlink(out_path);
        if (link(target, out_path) != 0) {
          free(target);
          die_errno("link", out_path);
        }
        free(target);
        break;
      }
      case '0':
      case '7':
        write_file_from_gz(gz, out_path, mode, size);
        gz_skip_padding(gz, size);
        size = 0;
        break;
      default:
        gz_skip_or_die(gz, size);
        gz_skip_padding(gz, size);
        size = 0;
        break;
    }

    if (size != 0) {
      gz_skip_or_die(gz, size);
      gz_skip_padding(gz, size);
    }

    free(name);
    free(linkname);
    free(out_path);
  }

  free(pending_long_name);
  free(pending_long_link);
  if (gzclose(gz) != Z_OK) {
    dief("failed to close archive: %s", archive);
  }
  return 0;
}

static void set_linux_path(void) {
  if (setenv("PATH", DEFAULT_LINUX_PATH, 1) != 0) {
    die_errno("setenv", "PATH");
  }
}

static char *shell_quote(const char *value) {
  size_t i;
  size_t len = 2;
  char *out;
  char *p;

  for (i = 0; value[i] != '\0'; i++) {
    len += (value[i] == '\'') ? 4 : 1;
  }

  out = xmalloc(len + 1);
  p = out;
  *p++ = '\'';
  for (i = 0; value[i] != '\0'; i++) {
    if (value[i] == '\'') {
      memcpy(p, "'\\''", 4);
      p += 4;
    } else {
      *p++ = value[i];
    }
  }
  *p++ = '\'';
  *p = '\0';
  return out;
}

typedef void (*pre_exec_fn)(void *);

static void spawn_pty_process(char *const *argv, pre_exec_fn pre_exec, void *opaque,
                              int *read_fd, int *write_fd, pid_t *pid_out) {
  int master_fd;
  char slave_name[PATH_MAX];
  pid_t pid;

  master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (master_fd < 0) {
    die_errno("posix_openpt", NULL);
  }
  if (grantpt(master_fd) != 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("grantpt", NULL);
  }
  if (unlockpt(master_fd) != 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("unlockpt", NULL);
  }
  if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("ptsname_r", NULL);
  }

  pid = fork();
  if (pid < 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("fork", NULL);
  }

  if (pid == 0) {
    int slave_fd;
    g_error_ctx = NULL;
    if (setsid() < 0) {
      fprintf(stderr, "setsid: %s\n", strerror(errno));
      _exit(127);
    }
    slave_fd = open(slave_name, O_RDWR);
    if (slave_fd < 0) {
      fprintf(stderr, "open pty slave: %s\n", strerror(errno));
      _exit(127);
    }
    (void)ioctl(slave_fd, TIOCSCTTY, 0);
    if (dup2(slave_fd, STDIN_FILENO) < 0 ||
        dup2(slave_fd, STDOUT_FILENO) < 0 ||
        dup2(slave_fd, STDERR_FILENO) < 0) {
      fprintf(stderr, "dup2: %s\n", strerror(errno));
      _exit(127);
    }
    if (slave_fd > STDERR_FILENO) {
      close(slave_fd);
    }
    close(master_fd);
    if (pre_exec != NULL) {
      pre_exec(opaque);
    }
    execvp(argv[0], argv);
    fprintf(stderr, "execvp %s: %s\n", argv[0], strerror(errno));
    _exit(127);
  }

  *read_fd = master_fd;
  *write_fd = dup(master_fd);
  if (*write_fd < 0) {
    int saved = errno;
    close(master_fd);
    kill(pid, SIGKILL);
    errno = saved;
    die_errno("dup", NULL);
  }
  *pid_out = pid;
}

struct proot_env {
  const char *tmp_dir;
  const char *loader_path;
};

static void proot_pre_exec(void *opaque) {
  struct proot_env *env = opaque;
  set_linux_path();
#ifdef PR_SET_DUMPABLE
  (void)prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
#endif
  if (setenv("PROOT_TMP_DIR", env->tmp_dir, 1) != 0) {
    die_errno("setenv", "PROOT_TMP_DIR");
  }
  if (env->loader_path != NULL && env->loader_path[0] != '\0') {
    if (setenv("PROOT_LOADER", env->loader_path, 1) != 0) {
      die_errno("setenv", "PROOT_LOADER");
    }
  }
}

static char *build_chroot_command(const char *root) {
  char *quoted_root = shell_quote(root);
  char *quoted_shell = shell_quote(DEFAULT_TOOL_SHELL);
  char *command = xasprintf(
      "PATH=%s; export PATH; "
      "ROOT=%s; "
      "mkdir -p \"$ROOT/proc\" \"$ROOT/sys\" \"$ROOT/dev\"; "
      "mount -t proc proc \"$ROOT/proc\" 2>/dev/null || true; "
      "mount --rbind /sys \"$ROOT/sys\" 2>/dev/null || true; "
      "mount --rbind /dev \"$ROOT/dev\" 2>/dev/null || true; "
      "cd \"$ROOT\"; "
      "chroot . /bin/sh -lc %s; "
      "RC=$?; "
      "umount -l \"$ROOT/dev\" 2>/dev/null || true; "
      "umount -l \"$ROOT/sys\" 2>/dev/null || true; "
      "umount -l \"$ROOT/proc\" 2>/dev/null || true; "
      "exit $RC",
      DEFAULT_LINUX_PATH,
      quoted_root,
      quoted_shell);
  free(quoted_root);
  free(quoted_shell);
  return command;
}

static int start_proot_session_impl(const char *root, const char *runtime_root,
                                    const char *proot_path,
                                    const char *loader_path,
                                    int *read_fd, int *write_fd, pid_t *pid_out) {
  char *tmp_dir = path_join2(runtime_root, "proot-tmp");
  struct proot_env env;
  char *argv[] = {
      (char *)proot_path,
      "--kill-on-exit",
      "-r",
      (char *)root,
      "-w",
      "/",
      "-b",
      "/dev",
      "-b",
      "/proc",
      "-b",
      "/sys",
      "/bin/sh",
      "-lc",
      (char *)DEFAULT_TOOL_SHELL,
      NULL,
  };

  if (proot_path == NULL || proot_path[0] == '\0') {
    dief("proot path is empty");
  }
  if (access(proot_path, X_OK) != 0) {
    die_errno("access", proot_path);
  }

  ensure_dir(runtime_root, 0700);
  if (rmrf_path(tmp_dir) != 0 && errno != ENOENT) {
    die_errno("rmrf", tmp_dir);
  }
  ensure_dir(tmp_dir, 0700);
  env.tmp_dir = tmp_dir;
  env.loader_path = loader_path;
  spawn_pty_process(argv, proot_pre_exec, &env, read_fd, write_fd, pid_out);

  free(tmp_dir);
  return 0;
}

static int start_chroot_session_impl(const char *root, int *read_fd, int *write_fd,
                                     pid_t *pid_out) {
  char *command = build_chroot_command(root);
  char *argv[] = {
      "su",
      "-c",
      command,
      NULL,
  };

  spawn_pty_process(argv, NULL, NULL, read_fd, write_fd, pid_out);
  free(command);
  return 0;
}

static void terminate_process_group(pid_t pid) {
  if (pid <= 0) {
    return;
  }
  kill(-pid, SIGTERM);
  usleep(250000);
  kill(-pid, SIGKILL);
}

typedef int (*protected_fn)(void *);

static int run_protected(protected_fn fn, void *opaque, char **error_out) {
  struct error_ctx ctx;

  ctx.message[0] = '\0';
  g_error_ctx = &ctx;
  if (setjmp(ctx.env) != 0) {
    g_error_ctx = NULL;
    if (error_out != NULL) {
      *error_out = xstrdup(ctx.message[0] == '\0' ? "unknown native error" : ctx.message);
    }
    return -1;
  }

  if (error_out != NULL) {
    *error_out = NULL;
  }
  if (fn(opaque) != 0) {
    g_error_ctx = NULL;
    if (error_out != NULL) {
      *error_out = xstrdup(ctx.message[0] == '\0' ? "native call failed" : ctx.message);
    }
    return -1;
  }
  g_error_ctx = NULL;
  return 0;
}

struct extract_args {
  const char *archive;
  const char *destination;
};

static int run_extract(void *opaque) {
  struct extract_args *args = opaque;
  return cmd_extract(args->archive, args->destination);
}

struct session_args {
  int mode;
  const char *root;
  const char *runtime;
  const char *proot_path;
  const char *proot_loader_path;
  int *read_fd;
  int *write_fd;
  pid_t *pid_out;
};

static int run_session(void *opaque) {
  struct session_args *args = opaque;

  if (args->mode == MODE_PROOT) {
    return start_proot_session_impl(
        args->root,
        args->runtime,
        args->proot_path,
        args->proot_loader_path,
        args->read_fd,
        args->write_fd,
        args->pid_out);
  }
  if (args->mode == MODE_CHROOT) {
    return start_chroot_session_impl(
        args->root,
        args->read_fd,
        args->write_fd,
        args->pid_out);
  }
  dief("unsupported mode: %d", args->mode);
  return -1;
}

static char *jstring_to_cstring(JNIEnv *env, jstring value) {
  const char *chars;
  char *copy;

  if (value == NULL) {
    return xstrdup("");
  }

  chars = (*env)->GetStringUTFChars(env, value, NULL);
  if (chars == NULL) {
    return NULL;
  }
  copy = xstrdup(chars);
  (*env)->ReleaseStringUTFChars(env, value, chars);
  return copy;
}

static void throw_io_exception(JNIEnv *env, const char *message) {
  jclass cls = (*env)->FindClass(env, "java/io/IOException");
  if (cls != NULL) {
    (*env)->ThrowNew(env, cls, message);
  }
}

JNIEXPORT void JNICALL
Java_io_github_bszapp_wlantool_bridge_RftoolBridge_extractRootfs(
    JNIEnv *env, jclass clazz, jstring archivePath, jstring destinationPath) {
  struct extract_args args;
  char *archive = NULL;
  char *destination = NULL;
  char *error = NULL;

  (void)clazz;
  archive = jstring_to_cstring(env, archivePath);
  destination = jstring_to_cstring(env, destinationPath);
  if (archive == NULL || destination == NULL) {
    free(archive);
    free(destination);
    return;
  }

  args.archive = archive;
  args.destination = destination;
  if (run_protected(run_extract, &args, &error) != 0) {
    throw_io_exception(env, error != NULL ? error : "rootfs extraction failed");
  }

  free(error);
  free(archive);
  free(destination);
}

JNIEXPORT jlongArray JNICALL
Java_io_github_bszapp_wlantool_bridge_RftoolBridge_startSession(
    JNIEnv *env, jclass clazz, jint mode, jstring rootfsPath, jstring runtimePath,
    jstring prootPath, jstring prootLoaderPath) {
  struct session_args args;
  char *root = NULL;
  char *runtime = NULL;
  char *proot = NULL;
  char *loader = NULL;
  char *error = NULL;
  pid_t pid = -1;
  int read_fd = -1;
  int write_fd = -1;
  jlongArray out;
  jlong values[3];

  (void)clazz;
  root = jstring_to_cstring(env, rootfsPath);
  runtime = jstring_to_cstring(env, runtimePath);
  proot = jstring_to_cstring(env, prootPath);
  loader = jstring_to_cstring(env, prootLoaderPath);
  if (root == NULL || runtime == NULL || proot == NULL || loader == NULL) {
    free(root);
    free(runtime);
    free(proot);
    free(loader);
    return NULL;
  }

  args.mode = (int)mode;
  args.root = root;
  args.runtime = runtime;
  args.proot_path = proot;
  args.proot_loader_path = loader;
  args.read_fd = &read_fd;
  args.write_fd = &write_fd;
  args.pid_out = &pid;

  if (run_protected(run_session, &args, &error) != 0) {
    throw_io_exception(env, error != NULL ? error : "session start failed");
    free(error);
    free(root);
    free(runtime);
    free(proot);
    free(loader);
    return NULL;
  }

  values[0] = (jlong)pid;
  values[1] = (jlong)read_fd;
  values[2] = (jlong)write_fd;
  out = (*env)->NewLongArray(env, 3);
  if (out != NULL) {
    (*env)->SetLongArrayRegion(env, out, 0, 3, values);
  }

  free(root);
  free(runtime);
  free(proot);
  free(loader);
  return out;
}

JNIEXPORT void JNICALL
Java_io_github_bszapp_wlantool_bridge_RftoolBridge_terminateProcess(
    JNIEnv *env, jclass clazz, jlong pid) {
  (void)env;
  (void)clazz;
  terminate_process_group((pid_t)pid);
}
