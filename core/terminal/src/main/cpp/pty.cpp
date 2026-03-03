#include "runtime.hpp"

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <unistd.h>

#include <string>
#include <vector>

int open_pty_pair(int* master_fd, int* slave_fd) {
    *master_fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (*master_fd < 0) {
        return -1;
    }

    if (grantpt(*master_fd) < 0 || unlockpt(*master_fd) < 0) {
        close(*master_fd);
        *master_fd = -1;
        return -1;
    }

    char slave_name[128] = {0};
    if (ptsname_r(*master_fd, slave_name, sizeof(slave_name)) != 0) {
        close(*master_fd);
        *master_fd = -1;
        return -1;
    }

    *slave_fd = open(slave_name, O_RDWR | O_NOCTTY);
    if (*slave_fd < 0) {
        close(*master_fd);
        *master_fd = -1;
        return -1;
    }

    return 0;
}

int start_shell_process(
    const std::string& proot_binary_path,
    const std::vector<std::string>& proot_args,
    int master_fd,
    int slave_fd,
    pid_t* child_pid) {
    const pid_t pid = fork();
    if (pid < 0) {
        return -1;
    }

    if (pid == 0) {
        setsid();
        ioctl(slave_fd, TIOCSCTTY, 0);

        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);

        close(master_fd);
        close(slave_fd);

        setenv("HOME", "/root", 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("SHELL", "/bin/bash", 1);
        setenv("TMPDIR", "/tmp", 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
        unsetenv("LD_PRELOAD");
        unsetenv("LD_LIBRARY_PATH");

        std::vector<char*> argv;
        argv.reserve(proot_args.size() + 2);
        argv.push_back(const_cast<char*>(proot_binary_path.c_str()));
        for (const auto& arg : proot_args) {
            argv.push_back(const_cast<char*>(arg.c_str()));
        }
        argv.push_back(nullptr);

        execv(proot_binary_path.c_str(), argv.data());
        _exit(127);
    }

    *child_pid = pid;
    return 0;
}
