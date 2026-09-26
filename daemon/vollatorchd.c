/*
 * vollatorchd — tiny root helper for VollaTorchStrength.
 *
 * Listens on an ABSTRACT unix domain socket ("\0vollatorchd"), blocking on
 * accept() (event-driven; zero polling, zero CPU when idle). Each client sends
 * a single ASCII integer + '\n' = desired torch level 0..31. The daemon clamps
 * it and writes it to BOTH flash channels:
 *     /sys/class/leds/mt6360_flash_ch1/brightness
 *     /sys/class/leds/mt6360_flash_ch2/brightness
 *
 * Runs as root (started by the KernelSU module's service.sh), so it can write
 * the sysfs nodes that the FlashDim / SystemUI process cannot.
 *
 * Protocol: one connection per command. Send "N\n". Reply: "OK\n" or "ERR\n".
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>

#define SOCK_NAME "vollatorchd"      /* abstract name (leading NUL added below) */
#define CH1 "/sys/class/leds/mt6360_flash_ch1/brightness"
#define CH2 "/sys/class/leds/mt6360_flash_ch2/brightness"
#define MAX_LEVEL 31

static int write_node(const char *path, int val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    char buf[16];
    int n = snprintf(buf, sizeof(buf), "%d\n", val);
    ssize_t w = write(fd, buf, n);
    close(fd);
    return (w == n) ? 0 : -1;
}

static int apply_level(int level) {
    if (level < 0) level = 0;
    if (level > MAX_LEVEL) level = MAX_LEVEL;
    int a = write_node(CH1, level);
    int b = write_node(CH2, level);
    return (a == 0 && b == 0) ? 0 : -1;
}

int main(void) {
    /* Detach a little: ignore SIGPIPE so a client hangup can't kill us. */
    signal(SIGPIPE, SIG_IGN);

    int srv = socket(AF_UNIX, SOCK_STREAM, 0);
    if (srv < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Abstract namespace: first byte of sun_path is NUL. */
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, SOCK_NAME, sizeof(addr.sun_path) - 2);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME);

    if (bind(srv, (struct sockaddr *)&addr, len) < 0) {
        perror("bind");
        return 1;
    }
    if (listen(srv, 8) < 0) {
        perror("listen");
        return 1;
    }

    for (;;) {
        int cli = accept(srv, NULL, NULL);   /* blocks — event-driven */
        if (cli < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            continue;
        }
        char buf[32];
        ssize_t r = read(cli, buf, sizeof(buf) - 1);
        if (r > 0) {
            buf[r] = '\0';
            int level = atoi(buf);
            int ok = apply_level(level);
            const char *reply = (ok == 0) ? "OK\n" : "ERR\n";
            (void)write(cli, reply, strlen(reply));
        }
        close(cli);
    }
    return 0;
}
