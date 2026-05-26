#define _GNU_SOURCE

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "zlib.h"

#define DEFAULT_LINUX_PATH "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
#define DEFAULT_TOOL_SHELL "PATH=" DEFAULT_LINUX_PATH "; export PATH; cd /wlantool && exec /bin/sh -i"

extern const unsigned char _binary_blob_proot_bin_start[];
extern const unsigned char _binary_blob_proot_bin_end[];

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

struct mount_record {
  char *target;
  bool active;
};

static void dief(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
  exit(1);
}

static void die_errno(const char *what, const char *arg) {
  if (arg != NULL) {
    fprintf(stderr, "%s: %s: %s\n", what, arg, strerror(errno));
  } else {
    fprintf(stderr, "%s: %s\n", what, strerror(errno));
  }
  exit(1);
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

static char *parent_dir_of(const char *path) {
  const char *slash = strrchr(path, '/');
  char *parent;

  if (slash == NULL) {
    return xstrdup(".");
  }
  if (slash == path) {
    return xstrdup("/");
  }
  parent = xmalloc((size_t)(slash - path) + 1);
  memcpy(parent, path, (size_t)(slash - path));
  parent[slash - path] = '\0';
  return parent;
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

static void mount_record_add(struct mount_record *records, size_t *count,
                             const char *target) {
  records[*count].target = xstrdup(target);
  records[*count].active = true;
  (*count)++;
}

static void mount_best_effort(struct mount_record *records, size_t *count,
                              const char *source, const char *target,
                              const char *fstype, unsigned long flags,
                              const void *data) {
  if (mount(source, target, fstype, flags, data) == 0) {
    mount_record_add(records, count, target);
  }
}

static void cleanup_mounts(struct mount_record *records, size_t count) {
  while (count > 0) {
    count--;
    if (records[count].active) {
      umount2(records[count].target, MNT_DETACH);
    }
    free(records[count].target);
  }
}

static int wait_child(pid_t pid) {
  int status;
  if (waitpid(pid, &status, 0) < 0) {
    die_errno("waitpid", NULL);
  }
  if (WIFEXITED(status)) {
    return WEXITSTATUS(status);
  }
  if (WIFSIGNALED(status)) {
    return 128 + WTERMSIG(status);
  }
  return 1;
}

static void set_linux_path(void) {
  if (setenv("PATH",
             DEFAULT_LINUX_PATH,
             1) != 0) {
    die_errno("setenv", "PATH");
  }
}

static int exec_chroot(const char *root, char *const *argv) {
  struct mount_record records[4];
  size_t mount_count = 0;
  char *proc_dir = path_join2(root, "proc");
  char *sys_dir = path_join2(root, "sys");
  char *dev_dir = path_join2(root, "dev");
  pid_t pid;
  int rc;

  if (geteuid() != 0) {
    dief("chroot requires euid 0");
  }

  ensure_dir(proc_dir, 0755);
  ensure_dir(sys_dir, 0755);
  ensure_dir(dev_dir, 0755);

  mount_best_effort(records, &mount_count, "proc", proc_dir, "proc", 0, NULL);
  mount_best_effort(records, &mount_count, "/sys", sys_dir, NULL, MS_BIND | MS_REC, NULL);
  mount_best_effort(records, &mount_count, "/dev", dev_dir, NULL, MS_BIND | MS_REC, NULL);

  pid = fork();
  if (pid < 0) {
    die_errno("fork", NULL);
  }

  if (pid == 0) {
    set_linux_path();
    if (chdir(root) != 0) {
      die_errno("chdir", root);
    }
    if (chroot(".") != 0) {
      die_errno("chroot", root);
    }
    if (chdir("/") != 0) {
      die_errno("chdir", "/");
    }
    execvp(argv[0], argv);
    die_errno("execvp", argv[0]);
  }

  rc = wait_child(pid);
  cleanup_mounts(records, mount_count);
  free(proc_dir);
  free(sys_dir);
  free(dev_dir);
  return rc;
}

static char *write_embedded_proot(const char *dir) {
  const unsigned char *start = _binary_blob_proot_bin_start;
  const unsigned char *end = _binary_blob_proot_bin_end;
  char *path = path_join2(dir, "proot.bin");
  int fd;
  size_t remaining = (size_t)(end - start);
  const unsigned char *p = start;

  ensure_dir(dir, 0700);
  fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0700);
  if (fd < 0) {
    die_errno("open", path);
  }

  while (remaining > 0) {
    ssize_t wrote = write(fd, p, remaining);
    if (wrote < 0) {
      int saved = errno;
      close(fd);
      errno = saved;
      die_errno("write", path);
    }
    p += wrote;
    remaining -= (size_t)wrote;
  }

  if (fchmod(fd, 0700) != 0) {
    int saved = errno;
    close(fd);
    errno = saved;
    die_errno("fchmod", path);
  }
  if (close(fd) != 0) {
    die_errno("close", path);
  }
  return path;
}

static int exec_proot(const char *root, int argc, char **argv) {
  char *parent = parent_dir_of(root);
  char *runtime_dir = path_join2(parent, ".rftool-proot");
  char *tmp_dir = path_join2(parent, ".rftool-proot-tmp");
  char *proot_path;
  char **child_argv;
  int child_argc;
  int rc;
  pid_t pid;

  rmrf_path(runtime_dir);
  rmrf_path(tmp_dir);
  ensure_dir(runtime_dir, 0700);
  ensure_dir(tmp_dir, 0700);
  proot_path = write_embedded_proot(runtime_dir);

  child_argc = argc + 11;
  child_argv = xcalloc((size_t)child_argc + 1, sizeof(char *));
  child_argv[0] = proot_path;
  child_argv[1] = "-r";
  child_argv[2] = (char *)root;
  child_argv[3] = "-w";
  child_argv[4] = "/";
  child_argv[5] = "-b";
  child_argv[6] = "/dev";
  child_argv[7] = "-b";
  child_argv[8] = "/proc";
  child_argv[9] = "-b";
  child_argv[10] = "/sys";
  memcpy(&child_argv[11], argv, (size_t)argc * sizeof(char *));

  pid = fork();
  if (pid < 0) {
    die_errno("fork", NULL);
  }
  if (pid == 0) {
    set_linux_path();
    if (setenv("PROOT_TMP_DIR", tmp_dir, 1) != 0) {
      die_errno("setenv", "PROOT_TMP_DIR");
    }
    execv(proot_path, child_argv);
    die_errno("execv", proot_path);
  }

  rc = wait_child(pid);
  unlink(proot_path);
  rmrf_path(runtime_dir);
  rmrf_path(tmp_dir);
  free(child_argv);
  free(proot_path);
  free(runtime_dir);
  free(tmp_dir);
  free(parent);
  return rc;
}

static void usage(const char *argv0) {
  fprintf(stderr,
          "usage:\n"
          "  %s extract ARCHIVE DEST\n"
          "  %s rmrf PATH\n"
          "  %s chroot ROOT [COMMAND [ARG...]]\n"
          "  %s proot ROOT [COMMAND [ARG...]]\n",
          argv0, argv0, argv0, argv0);
}

int main(int argc, char **argv) {
  static char *default_shell[] = { "/bin/sh", "-lc", DEFAULT_TOOL_SHELL, NULL };

  if (argc < 2) {
    usage(argv[0]);
    return 2;
  }

  if (strcmp(argv[1], "extract") == 0) {
    if (argc != 4) {
      usage(argv[0]);
      return 2;
    }
    return cmd_extract(argv[2], argv[3]);
  }

  if (strcmp(argv[1], "rmrf") == 0) {
    if (argc != 3) {
      usage(argv[0]);
      return 2;
    }
    if (rmrf_path(argv[2]) != 0 && errno != ENOENT) {
      die_errno("rmrf", argv[2]);
    }
    return 0;
  }

  if (strcmp(argv[1], "chroot") == 0) {
    if (argc < 3) {
      usage(argv[0]);
      return 2;
    }
    return exec_chroot(argv[2], argc > 3 ? &argv[3] : default_shell);
  }

  if (strcmp(argv[1], "proot") == 0) {
    if (argc < 3) {
      usage(argv[0]);
      return 2;
    }
    return exec_proot(argv[2], argc > 3 ? argc - 3 : 1,
                      argc > 3 ? &argv[3] : default_shell);
  }

  usage(argv[0]);
  return 2;
}
