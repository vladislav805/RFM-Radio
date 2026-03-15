#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "ctl_server.h"

int init_server(fm_srv_callback request_callback) {
    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        return -1;
    }

    struct sockaddr_in srv_addr;
    struct sockaddr_in cli_addr;
    memset(&srv_addr, 0, sizeof(srv_addr));
    memset(&cli_addr, 0, sizeof(cli_addr));

    srv_addr.sin_family = AF_INET;
    srv_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    srv_addr.sin_port = htons(CS_PORT);

    if (bind(sockfd, (struct sockaddr *)&srv_addr, sizeof(srv_addr)) < 0) {
        close(sockfd);
        return -2;
    }

    while (1) {
        char buf[CS_BUF];
        memset(buf, 0, sizeof(buf));

        socklen_t cli_len = sizeof(cli_addr);
        ssize_t cmd_len = recvfrom(sockfd, buf, sizeof(buf), 0, (struct sockaddr *)&cli_addr, &cli_len);
        if (cmd_len < 0) {
            continue;
        }

        if (strcmp(buf, "exit") == 0) {
            break;
        }

        response_t res = request_callback(buf);
        sendto(sockfd, res.data, strlen(res.data) + 1, 0, (struct sockaddr *)&cli_addr, cli_len);
    }

    close(sockfd);
    return 0;
}

bool send_interruption_info(int evt, const char *message) {
    char buf[CS_BUF];
    if (message == NULL) {
        message = "";
    }

    // Keep the historical "event_id<FF>payload" framing so the Java UDP
    // listener can stay backend-agnostic.
    snprintf(buf, sizeof(buf), "%d%c%s", evt, 0x0c, message);

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        return 0;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(CS_PORT_SRV);
    addr.sin_addr.s_addr = INADDR_ANY;

    const int ok = sendto(sock, buf, strlen(buf), MSG_CONFIRM, (struct sockaddr *)&addr, sizeof(addr)) >= 0;
    close(sock);
    return ok ? 1 : 0;
}
