#include "tailnet_connect_parser.h"

#include <stdint.h>
#include <string.h>

static int is_host_character(unsigned char value) {
    return (value >= 'a' && value <= 'z') ||
            (value >= 'A' && value <= 'Z') ||
            (value >= '0' && value <= '9') ||
            value == '.' || value == '-' || value == '_';
}

static int parse_port(const char *value, size_t length) {
    uint32_t port = 0;
    size_t index;
    if (length == 0 || length > 5) return 0;
    for (index = 0; index < length; ++index) {
        unsigned char character = (unsigned char) value[index];
        if (character < '0' || character > '9') return 0;
        port = port * 10u + (uint32_t) (character - '0');
    }
    return port > 0 && port <= 65535;
}

static int validate_authority(const char *authority, size_t length) {
    size_t host_start = 0;
    size_t host_end;
    size_t port_start;
    size_t index;
    if (length == 0 || length >= TAILNET_CONNECT_MAX_ADDRESS) return 0;
    if (authority[0] == '[') {
        host_start = 1;
        host_end = 1;
        while (host_end < length && authority[host_end] != ']') {
            unsigned char character = (unsigned char) authority[host_end];
            if (!((character >= '0' && character <= '9') ||
                    (character >= 'a' && character <= 'f') ||
                    (character >= 'A' && character <= 'F') ||
                    character == ':' || character == '.')) return 0;
            ++host_end;
        }
        if (host_end == host_start || host_end >= length ||
                host_end + 1 >= length || authority[host_end + 1] != ':') return 0;
        port_start = host_end + 2;
    } else {
        host_end = 0;
        while (host_end < length && authority[host_end] != ':') {
            if (!is_host_character((unsigned char) authority[host_end])) return 0;
            ++host_end;
        }
        if (host_end == 0 || host_end >= length) return 0;
        port_start = host_end + 1;
    }
    if (!parse_port(authority + port_start, length - port_start)) return 0;
    for (index = port_start; index < length; ++index) {
        if (authority[index] == ':' || authority[index] == '@' ||
                authority[index] == '/' || authority[index] == '\\') return 0;
    }
    return 1;
}

/* Extracts "host:port" (authority) from an absolute-form request line such
   as "GET http://host:port/path HTTP/1.1". Returns the authority length, or
   0 when the line is not an acceptable absolute-form request. The port is
   mandatory: Chromium always sends explicit ports in proxy request targets,
   and without one the tunnel destination would be ambiguous. */
static size_t parse_absolute_authority(const char *request, size_t length,
        char *authority, size_t authority_size) {
    static const char http_prefix[] = "http://";
    static const char https_prefix[] = "https://";
    static const char version_suffix[] = " HTTP/1.1";
    size_t line_end;
    size_t target_start;
    size_t target_end;
    size_t prefix_length;
    size_t authority_length;
    for (line_end = 0; line_end < length; ++line_end)
        if (request[line_end] == '\r' || request[line_end] == '\n') break;
    if (line_end < sizeof(version_suffix) - 1) return 0;
    if (memcmp(request + line_end - (sizeof(version_suffix) - 1),
            version_suffix, sizeof(version_suffix) - 1) != 0) return 0;
    /* Skip the request method ("GET", "POST", ...) and its space. */
    for (target_start = 0; target_start < line_end; ++target_start) {
        unsigned char character = (unsigned char) request[target_start];
        if (character == ' ' || character == '\t') break;
        if (character < 0x21 || character == 0x7f) return 0;
    }
    if (target_start >= line_end) return 0;
    ++target_start; /* step past the space */
    if (line_end - target_start < sizeof(http_prefix) - 1) return 0;
    if (memcmp(request + target_start, http_prefix, sizeof(http_prefix) - 1) == 0) {
        prefix_length = sizeof(http_prefix) - 1;
    } else if (line_end - target_start >= sizeof(https_prefix) - 1 &&
            memcmp(request + target_start, https_prefix, sizeof(https_prefix) - 1) == 0) {
        prefix_length = sizeof(https_prefix) - 1;
    } else {
        return 0;
    }
    target_start += prefix_length;
    for (target_end = target_start; target_end < line_end; ++target_end) {
        unsigned char character = (unsigned char) request[target_end];
        if (character == '/' || character == '?' || character == '#' ||
                character == ' ' || character == '\t')
            break;
    }
    authority_length = target_end - target_start;
    if (authority_length == 0 || authority_length >= authority_size) return 0;
    if (!validate_authority(request + target_start, authority_length)) return 0;
    memcpy(authority, request + target_start, authority_length);
    authority[authority_length] = '\0';
    return authority_length;
}

tailnet_connect_result tailnet_parse_connect_request(
        const char *request, size_t length, tailnet_connect_target *target) {
    static const char method[] = "CONNECT ";
    static const char version[] = " HTTP/1.1\r\n";
    size_t header_end = 0;
    size_t line_end = 0;
    size_t authority_start = sizeof(method) - 1;
    size_t authority_length;
    size_t index;
    if (request == NULL || target == NULL) return TAILNET_CONNECT_BAD_REQUEST;
    memset(target, 0, sizeof(*target));
    if (length > TAILNET_CONNECT_MAX_HEADER) return TAILNET_CONNECT_TOO_LARGE;
    for (index = 0; index + 3 < length; ++index) {
        if (request[index] == '\r' && request[index + 1] == '\n' &&
                request[index + 2] == '\r' && request[index + 3] == '\n') {
            header_end = index + 4;
            break;
        }
    }
    if (header_end == 0) return length == TAILNET_CONNECT_MAX_HEADER
            ? TAILNET_CONNECT_TOO_LARGE : TAILNET_CONNECT_INCOMPLETE;
    for (index = 0; index < header_end; ++index) {
        unsigned char character = (unsigned char) request[index];
        if (character == 0 || character == 0x7f ||
                (character < 0x20 && character != '\r' && character != '\n' && character != '\t'))
            return TAILNET_CONNECT_BAD_REQUEST;
        if (character == '\r' && (index + 1 >= header_end || request[index + 1] != '\n'))
            return TAILNET_CONNECT_BAD_REQUEST;
        if (character == '\n' && (index == 0 || request[index - 1] != '\r'))
            return TAILNET_CONNECT_BAD_REQUEST;
    }
    if (header_end < sizeof(method) - 1 ||
            memcmp(request, method, sizeof(method) - 1) != 0) {
        /* Not a CONNECT request: accept absolute-form http(s) request targets
           so plain-HTTP navigations can also ride the Tailnet tunnel. */
        if (parse_absolute_authority(request, length > header_end ? header_end : length,
                target->address, sizeof(target->address)) != 0) {
            target->consumed = 0;
            return TAILNET_CONNECT_ABSOLUTE_FORM;
        }
        return TAILNET_CONNECT_METHOD_NOT_ALLOWED;
    }
    for (index = authority_start; index + 1 < header_end; ++index) {
        if (request[index] == '\r' && request[index + 1] == '\n') {
            line_end = index;
            break;
        }
    }
    if (line_end <= authority_start + sizeof(version) - 1 ||
            memcmp(request + line_end - (sizeof(version) - 3),
                    version, sizeof(version) - 1) != 0)
        return TAILNET_CONNECT_BAD_REQUEST;
    authority_length = line_end - authority_start - (sizeof(version) - 3);
    if (!validate_authority(request + authority_start, authority_length))
        return TAILNET_CONNECT_BAD_REQUEST;
    memcpy(target->address, request + authority_start, authority_length);
    target->address[authority_length] = '\0';
    target->consumed = header_end;
    return TAILNET_CONNECT_OK;
}