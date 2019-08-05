#include <string.h>
#include <stdlib.h>
#include <netinet/in.h>
#include <netdb.h>
#include <unistd.h>
#include <errno.h>
#include "fmsrv.h"
#include "fmcommon.h"

int init_server(fm_srv_callback callback) {
	printf("starting server...");
	int sockfd = socket(AF_INET, SOCK_DGRAM, 0);

	// Если не удалось слушать порт (например, он уже занят)
	if (sockfd < 0) {
		printf("socket init failed\n");
		return -1;
	}

	struct sockaddr_in srv_addr = {0};
	struct sockaddr_in cli_addr = {0};

	socklen_t srv_len = sizeof(struct sockaddr_in);
	socklen_t cli_len;

	srv_addr.sin_family = AF_INET;
	srv_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	srv_addr.sin_port = htons(CS_PORT);

	if (bind(sockfd, (struct sockaddr*) &srv_addr, srv_len) < 0) {
		printf("Bind error\n");
		return -2;
	}

	boolean working = TRUE;

	ssize_t cmd_len;
	ssize_t rcv_len;
	size_t res_len;
	char buf[CS_BUF];

	printf("server started\n");

	const char* exit = "exit";

	while (working) {
		memset((char*) &cli_addr, 0, sizeof(cli_addr));
		memset((char*) &buf, 0, sizeof(buf));

		cli_len = sizeof(cli_addr);

		cmd_len = recvfrom(sockfd, buf, sizeof(buf), 0, (struct sockaddr *) &cli_addr, &cli_len);

		if (cmd_len < 0) {
			printf("recvfrom error\n");
			continue;
		}

		printf("client received data [%s]\n", buf);

		if (strcmp(buf, exit) == 0) {
			working = FALSE;
			break;
		}

		srv_response res = callback(buf);
		res_len = strlen(res.data) * sizeof(char) + 1;

		rcv_len = sendto(sockfd, res.data, res_len, 0, (struct sockaddr *) &cli_addr, cli_len);
		if (rcv_len < 0) {
			printf("\n");
		}
		printf("sent %zd bytes.\n", rcv_len);

		//free(&res.data);
	}

	close(sockfd);
	printf("exited\n");
	return 0;
}

void send_interruption_info(int evt, char* message) {
	int sock;
	struct sockaddr_in addr;
	char buf[CS_BUF];

	if (message == NULL) {
		message = "";
	}

	sprintf(buf, "%d\n%s", evt, message);

	addr.sin_family = AF_INET;
	addr.sin_port = htons(CS_PORT_SRV); // порт
	addr.sin_addr.s_addr = INADDR_ANY; // адрес

	sock = socket(AF_INET, SOCK_DGRAM, 0);
	if (sock < 0) {
		printf("socket < 0\n");
		return;
	}

	// Отправка того, что ввел пользователь
	ssize_t bytes = sendto(sock, buf, strlen(buf), MSG_CONFIRM, (struct sockaddr*) &addr, sizeof(addr));

	close(sock);
}