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

int start_shell_process(const std::string& rootfs_path, int master_fd, int slave_fd, pid_t* child_pid) {
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

        chdir(rootfs_path.c_str());

        const std::string shell_path = rootfs_path + "/bin/sh";
        const std::string home_path = rootfs_path + "/home";

        std::vector<char*> argv;
        argv.push_back(const_cast<char*>(shell_path.c_str()));
        argv.push_back(const_cast<char*>("-i"));
        argv.push_back(nullptr);

        std::vector<std::string> env_values;
        env_values.emplace_back("HOME=" + home_path);
        env_values.emplace_back("PATH=/usr/bin:/bin");
        env_values.emplace_back("TERM=xterm-256color");
        env_values.emplace_back("PWD=" + rootfs_path);

        std::vector<char*> envp;
        for (auto& value : env_values) {
            envp.push_back(const_cast<char*>(value.c_str()));
        }
        envp.push_back(nullptr);

        execve(shell_path.c_str(), argv.data(), envp.data());
        _exit(127);
    }

    *child_pid = pid;
    return 0;
}
