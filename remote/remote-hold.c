#include <errno.h>
#include <fcntl.h>
#include <glob.h>
#include <linux/input.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <time.h>
#include <unistd.h>

static int find_remote(void) {
    glob_t devices;
    if (glob("/dev/input/event*", 0, NULL, &devices) != 0) return -1;
    int remote = -1;
    for (size_t i = 0; i < devices.gl_pathc; i++) {
        int fd = open(devices.gl_pathv[i], O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        struct input_id id;
        char name[64] = {0};
        if (ioctl(fd, EVIOCGID, &id) == 0 && id.bustype == BUS_BLUETOOTH
            && id.vendor == 0x057a && id.product == 0x0058
            && ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0
            && strcmp(name, "Yandex Remote") == 0) {
            if (remote >= 0) {
                close(remote);
                close(fd);
                remote = -1;
                break;
            }
            remote = fd;
        } else {
            close(fd);
        }
    }
    globfree(&devices);
    return remote;
}

static void launch_overlay(void) {
    pid_t child = fork();
    if (child < 0) {
        perror("assistant fork");
    } else if (child == 0) {
        execl("/system/bin/am", "am", "start", "--user", "0", "-f", "0x34000000",
            "-a", "org.yanalice.voice.REMOTE_HOLD",
            "-n", "org.yanalice.voice/.AssistantOverlayActivity", "--ez", "start", "true",
            (char *)NULL);
        _exit(127);
    }
}

static void watch(int fd) {
    int clock = CLOCK_MONOTONIC;
    if (ioctl(fd, EVIOCSCLOCKID, &clock) < 0) {
        perror("remote event clock");
        return;
    }
    fprintf(stderr, "Remote input attached\n");
    uint16_t key = 0;
    int64_t down = 0;
    struct input_event event;
    for (;;) {
        ssize_t count = read(fd, &event, sizeof(event));
        if (count < 0 && errno == EINTR) continue;
        if (count != sizeof(event)) return;
        if (event.type == EV_SYN && event.code == SYN_DROPPED) down = 0;
        if (event.type != EV_KEY) continue;
        int64_t now = (int64_t)event.time.tv_sec * 1000 + event.time.tv_usec / 1000;
        if (event.code != KEY_SEARCH && event.code != KEY_VOICECOMMAND) {
            if (event.value == 1) down = 0;
            continue;
        }
        if (event.value == 1 && !down) {
            key = event.code;
            down = now;
        } else if (event.value == 0 && down && event.code == key) {
            int64_t hold = now - down;
            down = 0;
            if (hold < 550 || hold > 8000) continue;
            fprintf(stderr, "Assistant hold accepted (%lld ms)\n", (long long)hold);
            launch_overlay();
        }
    }
}

int main(void) {
    umask(077);
    int lock = open("/data/local/tmp/max-station-remote.lock", O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) return 1;
    signal(SIGCHLD, SIG_IGN);
    for (;;) {
        int fd = find_remote();
        if (fd >= 0) {
            watch(fd);
            close(fd);
        }
        sleep(2);
    }
}
