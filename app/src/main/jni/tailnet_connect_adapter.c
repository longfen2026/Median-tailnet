#include "tailnet_connect_adapter.h"

#include "tailnet_connect_parser.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define CONNECT_BACKLOG 16
#define RELAY_BUFFER_SIZE 16384

typedef struct adapter_connection {
    struct tailnet_connect_adapter *adapter;
    struct adapter_connection *next;
    int client_fd;
    int tailnet_fd;
} adapter_connection;

struct tailnet_connect_adapter {
    tailscale node;
    tailnet_domain_policy *policy;
    int listener_fd;
    uint16_t port;
    int stopping;
    int accept_thread_started;
    size_t worker_count;
    pthread_t accept_thread;
    pthread_mutex_t mutex;
    pthread_cond_t workers_done;
    adapter_connection *connections;
};

static int connect_direct(const char *host, unsigned short port) {
    struct addrinfo hints;
    struct addrinfo *addresses = NULL;
    struct addrinfo *current;
    char service[6];
    int result = -1;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    snprintf(service, sizeof(service), "%u", port);
    if (getaddrinfo(host, service, &hints, &addresses) != 0) return -1;
    for (current = addresses; current != NULL; current = current->ai_next) {
        int candidate = socket(current->ai_family,
                current->ai_socktype | SOCK_CLOEXEC, current->ai_protocol);
        if (candidate < 0) continue;
        if (connect(candidate, current->ai_addr, current->ai_addrlen) == 0) {
            result = candidate;
            break;
        }
        close(candidate);
    }
    freeaddrinfo(addresses);
    return result;
}

static void close_fd(int *fd) {
    if (*fd >= 0) {
        shutdown(*fd, SHUT_RDWR);
        close(*fd);
        *fd = -1;
    }
}

static int write_all(int fd, const char *buffer, size_t length, int socket_fd) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = socket_fd
                ? send(fd, buffer + offset, length - offset, MSG_NOSIGNAL)
                : write(fd, buffer + offset, length - offset);
        if (written > 0) {
            offset += (size_t) written;
        } else if (written < 0 && errno == EINTR) {
            continue;
        } else {
            return -1;
        }
    }
    return 0;
}

static void send_response(int client_fd, const char *status) {
    char response[128];
    int length = snprintf(response, sizeof(response),
            "HTTP/1.1 %s\r\nConnection: close\r\nContent-Length: 0\r\n\r\n", status);
    if (length > 0 && (size_t) length < sizeof(response))
        write_all(client_fd, response, (size_t) length, 1);
}

static int relay_once(int source, int destination, int destination_is_socket) {
    char buffer[RELAY_BUFFER_SIZE];
    ssize_t length;
    do {
        length = read(source, buffer, sizeof(buffer));
    } while (length < 0 && errno == EINTR);
    if (length <= 0) return -1;
    return write_all(destination, buffer, (size_t) length, destination_is_socket);
}

static void relay_connection(adapter_connection *connection) {
    struct pollfd descriptors[2];
    descriptors[0].fd = connection->client_fd;
    descriptors[0].events = POLLIN;
    descriptors[1].fd = connection->tailnet_fd;
    descriptors[1].events = POLLIN;
    for (;;) {
        int result;
        do {
            result = poll(descriptors, 2, -1);
        } while (result < 0 && errno == EINTR);
        if (result <= 0) return;
        if ((descriptors[0].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0 ||
                (descriptors[1].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) return;
        if ((descriptors[0].revents & POLLIN) != 0 &&
                relay_once(connection->client_fd, connection->tailnet_fd, 0) != 0) return;
        if ((descriptors[1].revents & POLLIN) != 0 &&
                relay_once(connection->tailnet_fd, connection->client_fd, 1) != 0) return;
    }
}

static void remove_connection(adapter_connection *connection) {
    tailnet_connect_adapter *adapter = connection->adapter;
    adapter_connection **current;
    pthread_mutex_lock(&adapter->mutex);
    current = &adapter->connections;
    while (*current != NULL && *current != connection) current = &(*current)->next;
    if (*current == connection) *current = connection->next;
    if (adapter->worker_count > 0) --adapter->worker_count;
    pthread_cond_signal(&adapter->workers_done);
    pthread_mutex_unlock(&adapter->mutex);
}

static void *connection_main(void *argument) {
    adapter_connection *connection = argument;
    char request[TAILNET_CONNECT_MAX_HEADER];
    size_t request_length = 0;
    tailnet_connect_target target;
    tailnet_connect_result parsed = TAILNET_CONNECT_INCOMPLETE;
    while (parsed == TAILNET_CONNECT_INCOMPLETE) {
        parsed = tailnet_parse_connect_request(request, request_length, &target);
        if (parsed == TAILNET_CONNECT_INCOMPLETE) {
            ssize_t received = recv(connection->client_fd, request + request_length,
                    TAILNET_CONNECT_MAX_HEADER - request_length, 0);
            if (received > 0) {
                request_length += (size_t) received;
            } else if (received < 0 && errno == EINTR) {
                continue;
            } else {
                parsed = TAILNET_CONNECT_BAD_REQUEST;
                break;
            }
        }
    }
    if (parsed == TAILNET_CONNECT_OK || parsed == TAILNET_CONNECT_ABSOLUTE_FORM) {
        int upstream_fd = -1;
        int tailnet_route = tailnet_domain_policy_matches(
            connection->adapter->policy, target.host);
        int connected = tailnet_route
            ? tailscale_dial(connection->adapter->node, "tcp",
                target.address, &upstream_fd) == 0
            : (upstream_fd = connect_direct(target.host, target.port)) >= 0;
        if (connected) {
            pthread_mutex_lock(&connection->adapter->mutex);
            if (!connection->adapter->stopping) connection->tailnet_fd = upstream_fd;
            pthread_mutex_unlock(&connection->adapter->mutex);
            if (connection->tailnet_fd >= 0) {
                if (parsed == TAILNET_CONNECT_OK) {
                    send_response(connection->client_fd, "200 Connection Established");
                    if (request_length > target.consumed &&
                            write_all(connection->tailnet_fd, request + target.consumed,
                                    request_length - target.consumed, 0) != 0)
                        goto done;
                } else if (request_length > 0 &&
                        write_all(connection->tailnet_fd, request, request_length, 0) != 0) {
                    goto done;
                }
                relay_connection(connection);
            } else {
                close(upstream_fd);
            }
        } else {
            send_response(connection->client_fd, "502 Bad Gateway");
        }
    } else if (parsed == TAILNET_CONNECT_METHOD_NOT_ALLOWED) {
        send_response(connection->client_fd, "405 Method Not Allowed");
    } else if (parsed == TAILNET_CONNECT_TOO_LARGE) {
        send_response(connection->client_fd, "431 Request Header Fields Too Large");
    } else {
        send_response(connection->client_fd, "400 Bad Request");
    }
done:
    pthread_mutex_lock(&connection->adapter->mutex);
    close_fd(&connection->client_fd);
    close_fd(&connection->tailnet_fd);
    pthread_mutex_unlock(&connection->adapter->mutex);
    remove_connection(connection);
    free(connection);
    return NULL;
}

static void *accept_main(void *argument) {
    tailnet_connect_adapter *adapter = argument;
    for (;;) {
        int client_fd = accept(adapter->listener_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            return NULL;
        }
        adapter_connection *connection = calloc(1, sizeof(*connection));
        if (connection == NULL) {
            close(client_fd);
            continue;
        }
        connection->adapter = adapter;
        connection->client_fd = client_fd;
        connection->tailnet_fd = -1;
        pthread_t worker;
        pthread_mutex_lock(&adapter->mutex);
        if (adapter->stopping) {
            pthread_mutex_unlock(&adapter->mutex);
            close(client_fd);
            free(connection);
            return NULL;
        }
        connection->next = adapter->connections;
        adapter->connections = connection;
        ++adapter->worker_count;
        pthread_mutex_unlock(&adapter->mutex);
        if (pthread_create(&worker, NULL, connection_main, connection) != 0) {
            close_fd(&connection->client_fd);
            remove_connection(connection);
            free(connection);
        } else {
            pthread_detach(worker);
        }
    }
}

tailnet_connect_adapter *tailnet_connect_adapter_start(
    tailscale node, tailnet_domain_policy *policy) {
    tailnet_connect_adapter *adapter = calloc(1, sizeof(*adapter));
    struct sockaddr_in address;
    socklen_t address_length = sizeof(address);
    if (adapter == NULL || policy == NULL) {
        free(adapter);
        return NULL;
    }
    adapter->node = node;
    adapter->policy = policy;
    adapter->listener_fd = -1;
    pthread_mutex_init(&adapter->mutex, NULL);
    pthread_cond_init(&adapter->workers_done, NULL);
    adapter->listener_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = 0;
    if (adapter->listener_fd < 0 ||
            bind(adapter->listener_fd, (struct sockaddr *) &address, sizeof(address)) != 0 ||
            listen(adapter->listener_fd, CONNECT_BACKLOG) != 0 ||
            getsockname(adapter->listener_fd, (struct sockaddr *) &address, &address_length) != 0)
        goto fail;
    adapter->port = ntohs(address.sin_port);
    if (pthread_create(&adapter->accept_thread, NULL, accept_main, adapter) != 0) goto fail;
    adapter->accept_thread_started = 1;
    return adapter;

fail:
    close_fd(&adapter->listener_fd);
    pthread_cond_destroy(&adapter->workers_done);
    pthread_mutex_destroy(&adapter->mutex);
    tailnet_domain_policy_destroy(adapter->policy);
    free(adapter);
    return NULL;
}

int tailnet_connect_adapter_address(
        tailnet_connect_adapter *adapter, char *buffer, size_t buffer_size) {
    int length;
    if (adapter == NULL || buffer == NULL || buffer_size == 0) return -1;
    length = snprintf(buffer, buffer_size, "http://127.0.0.1:%u", adapter->port);
    return length > 0 && (size_t) length < buffer_size ? 0 : -1;
}

tailscale tailnet_connect_adapter_node(tailnet_connect_adapter *adapter) {
    return adapter == NULL ? -1 : adapter->node;
}

void tailnet_connect_adapter_stop(tailnet_connect_adapter *adapter) {
    adapter_connection *connection;
    if (adapter == NULL) return;
    pthread_mutex_lock(&adapter->mutex);
    adapter->stopping = 1;
    close_fd(&adapter->listener_fd);
    for (connection = adapter->connections; connection != NULL; connection = connection->next) {
        if (connection->client_fd >= 0) shutdown(connection->client_fd, SHUT_RDWR);
        if (connection->tailnet_fd >= 0) shutdown(connection->tailnet_fd, SHUT_RDWR);
    }
    pthread_mutex_unlock(&adapter->mutex);
    tailscale_close(adapter->node);
    if (adapter->accept_thread_started) pthread_join(adapter->accept_thread, NULL);
    pthread_mutex_lock(&adapter->mutex);
    while (adapter->worker_count != 0)
        pthread_cond_wait(&adapter->workers_done, &adapter->mutex);
    pthread_mutex_unlock(&adapter->mutex);
    pthread_cond_destroy(&adapter->workers_done);
    pthread_mutex_destroy(&adapter->mutex);
    tailnet_domain_policy_destroy(adapter->policy);
    free(adapter);
}