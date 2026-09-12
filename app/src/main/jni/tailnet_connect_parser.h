#ifndef MEDIAN_TAILNET_CONNECT_PARSER_H
#define MEDIAN_TAILNET_CONNECT_PARSER_H

#include <stddef.h>

#define TAILNET_CONNECT_MAX_HEADER 8192
#define TAILNET_CONNECT_MAX_ADDRESS 512

typedef enum {
    TAILNET_CONNECT_OK = 0,
    TAILNET_CONNECT_INCOMPLETE,
    TAILNET_CONNECT_TOO_LARGE,
    TAILNET_CONNECT_BAD_REQUEST,
    TAILNET_CONNECT_METHOD_NOT_ALLOWED,
    /* Absolute-form request (e.g. "GET http://host:port/path HTTP/1.1")
       accepted for plain-HTTP tunneling through the Tailnet node. */
    TAILNET_CONNECT_ABSOLUTE_FORM
} tailnet_connect_result;

typedef struct {
    char address[TAILNET_CONNECT_MAX_ADDRESS];
    size_t consumed;
} tailnet_connect_target;

tailnet_connect_result tailnet_parse_connect_request(
        const char *request, size_t length, tailnet_connect_target *target);

#endif