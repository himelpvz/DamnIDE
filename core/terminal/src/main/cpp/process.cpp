#include "runtime.hpp"

#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>
#include <vector>

void reader_loop() {
    std::vector<char> buffer(4096);

    while (state().running.load()) {
        pollfd pfd{};
        pfd.fd = state().master_fd;
        pfd.events = POLLIN;

        const int ready = poll(&pfd, 1, -1);

        if (!state().running.load()) {
            break;
        }

        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            set_last_error(std::string("poll failed: ") + std::strerror(errno));
            emit_error(get_last_error());
            break;
        }

        if ((pfd.revents & POLLIN) == 0) {
            continue;
        }

        const ssize_t count = read(state().master_fd, buffer.data(), buffer.size());
        if (count > 0) {
            emit_output(std::string(buffer.data(), static_cast<size_t>(count)));
            continue;
        }

        if (count == 0) {
            break;
        }

        if (errno != EINTR) {
            set_last_error(std::string("read failed: ") + std::strerror(errno));
            emit_error(get_last_error());
            break;
        }
    }

    int status = 0;
    if (state().child_pid > 0) {
        waitpid(state().child_pid, &status, 0);
        const int code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
        emit_exit(code);
    }

    std::lock_guard<std::mutex> lock(state().state_mutex);
    state().running.store(false);
    state().child_pid = -1;
    if (state().master_fd >= 0) {
        close(state().master_fd);
        state().master_fd = -1;
    }
}

void stop_process_by_pid(pid_t child_pid) {
    std::lock_guard<std::mutex> lock(state().state_mutex);

    state().running.store(false);

    if (state().master_fd >= 0) {
        close(state().master_fd);
        state().master_fd = -1;
    }

    if (child_pid > 0) {
        kill(child_pid, SIGTERM);
        int status = 0;
        waitpid(child_pid, &status, 0);
    }

    state().child_pid = -1;
}
