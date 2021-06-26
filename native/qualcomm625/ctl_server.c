#include <string.h>
#include <stdlib.h>
#include <netinet/in.h>
#include <netdb.h>
#include <unistd.h>
#include "ctl_server.h"
#include "fmcommon.h"

/**
 * Open server for receiving control requests
 * @param request_callback Callback - handler of each control request
 * @return
 */
int init_server(fm_srv_callback request_callback) {
	printf("server          : starting server...\n");

	// Create socket
	int sockfd = socket(AF_INET, SOCK_DGRAM, 0);

	// Если не удалось слушать порт (например, он уже занят)
	if (sockfd < 0) {
		printf("server          : socket init failed\n");
		return -1;
	}

	struct sockaddr_in srv_addr = {0};
	struct sockaddr_in cli_addr = {0};

	socklen_t srv_len = sizeof(struct sockaddr_in);
	socklen_t cli_len;

	srv_addr.sin_family = AF_INET;
	srv_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	srv_addr.sin_port = htons(CS_PORT);

	// Bind socket to port
	if (bind(sockfd, (struct sockaddr*) &srv_addr, srv_len) < 0) {
		printf("server          : bind error\n");
		return -2;
	}

	boolean working = TRUE;

	ssize_t cmd_len;
	ssize_t rcv_len;
	size_t res_len;
	char buf[CS_BUF];

	printf("server          : started\n");

	const char* exit = "exit";

	while (working) {
		// Clear
		memset((char*) &cli_addr, 0, sizeof(cli_addr));
		memset((char*) &buf, 0, sizeof(buf));

		cli_len = sizeof(cli_addr);

		cmd_len = recvfrom(sockfd, buf, sizeof(buf), 0, (struct sockaddr *) &cli_addr, &cli_len);

		if (cmd_len < 0) {
			printf("server          : recvfrom error\n");
			continue;
		}

		printf("server          : client received `%s`\n", buf);

		if (strcmp(buf, exit) == 0) {
			working = FALSE;
			break;
		}

		response_t res = request_callback(buf);
		res_len = strlen(res.data) * sizeof(char) + 1;

		// Send response after execute callback
		//rcv_len =
		sendto(sockfd, res.data, res_len, 0, (struct sockaddr*) &cli_addr, cli_len);

		// printf("sent %zd bytes.\n", rcv_len);

		//free(&res.data);
	}

	// Close socket
	close(sockfd);
	printf("server          : closed\n");
	return 0;
}

const char MESSAGE_DELIMITER = 0x0c;

boolean send_interruption_info(int evt, char* message) {
	int sock;
	struct sockaddr_in addr;
	char buf[CS_BUF];

	// If message is NULL - replace it by empty string
	if (message == NULL) {
		message = "";
	}

	// Stringify message with format "${event_id}\x0c${message}"
	sprintf(buf, "%d%c%s", evt, MESSAGE_DELIMITER, message);

	addr.sin_family = AF_INET;
	addr.sin_port = htons(CS_PORT_SRV); // port
	addr.sin_addr.s_addr = INADDR_ANY; // address

	// Open connection
	sock = socket(AF_INET, SOCK_DGRAM, 0);
	if (sock < 0) {
		printf("evt_send: socket < 0\n");
		return FALSE;
	}

	// Send data to server on application (Android Service)
	sendto(sock, buf, strlen(buf), MSG_CONFIRM, (struct sockaddr*) &addr, sizeof(addr));

	// Close connection
	close(sock);

	return TRUE;
}
