#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "ctl_server.h"

int init_server(fm_srv_callback request_callback) {
    FM2_LOGI("server starting on 127.0.0.1:%d", CS_PORT);

    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        FM2_PERROR("socket init failed");
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
        FM2_PERROR("bind failed");
        close(sockfd);
        return -2;
    }

    FM2_LOGI("server started, sockfd=%d", sockfd);

    while (TRUE) {
        char buf[CS_BUF];
        memset(buf, 0, sizeof(buf));

        socklen_t cli_len = sizeof(cli_addr);
        ssize_t cmd_len = recvfrom(sockfd, buf, sizeof(buf), 0, (struct sockaddr *)&cli_addr, &cli_len);
        if (cmd_len < 0) {
            FM2_PERROR("recvfrom failed");
            continue;
        }

        if (strcmp(buf, "exit") == 0) {
            FM2_LOGI("received exit command");
            break;
        }

        FM2_LOGI("received command len=%zd payload=`%s`", cmd_len, buf);
        response_t res = request_callback(buf);
        FM2_LOGI("sending response code=%d payload=`%s`", res.code, res.data);
        sendto(sockfd, res.data, strlen(res.data) + 1, 0, (struct sockaddr *)&cli_addr, cli_len);
    }

    close(sockfd);
    FM2_LOGI("server closed");
    return 0;
}

boolean send_interruption_info(int evt, const char *message) {
    char buf[CS_BUF];
    if (message == NULL) {
        message = "";
    }

    snprintf(buf, sizeof(buf), "%d%c%s", evt, 0x0c, message);

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        FM2_PERROR("event socket init failed");
        return FALSE;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(CS_PORT_SRV);
    addr.sin_addr.s_addr = INADDR_ANY;

    FM2_LOGI("sending event id=%d payload=`%s`", evt, message);
    if (sendto(sock, buf, strlen(buf), MSG_CONFIRM, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        FM2_PERROR("event send failed");
        close(sock);
        return FALSE;
    }
    close(sock);
    return TRUE;
}
