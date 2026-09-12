#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../app/src/main/jni/tailnet_connect_parser.h"

static void fail(const char *message) {
        exit(1);
}

static void expect_target(const char *request, const char *expected,
        const char *expected_host, unsigned short expected_port) {
    tailnet_connect_target target;
    tailnet_connect_result result = tailnet_parse_connect_request(
            request, strlen(request), &target);
        if (result != TAILNET_CONNECT_OK) fail("valid CONNECT request rejected");
        if (strcmp(target.address, expected) != 0) fail("CONNECT target changed");
        if (strcmp(target.host, expected_host) != 0) fail("CONNECT host changed");
        if (target.port != expected_port) fail("CONNECT port changed");
        if (target.consumed != strlen(request)) fail("header boundary was not reported");
}

static void expect_result(const char *request, size_t length,
                tailnet_connect_result expected) {
        tailnet_connect_target target;
        tailnet_connect_result result = tailnet_parse_connect_request(request, length, &target);
        if (result != expected) fail("unexpected parser result");
        if (target.address[0] != '\0' || target.host[0] != '\0' || target.port != 0 ||
                        target.consumed != 0)
                fail("rejected request exposed a target");
}
static void expect_absolute(const char *request, const char *expected,
                const char *expected_host, unsigned short expected_port) {
        tailnet_connect_target target;
        tailnet_connect_result result = tailnet_parse_connect_request(
            request, strlen(request), &target);
    if (result != TAILNET_CONNECT_ABSOLUTE_FORM) fail("absolute-form request rejected");
    if (strcmp(target.address, expected) != 0) fail("absolute-form target changed");
    if (strcmp(target.host, expected_host) != 0) fail("absolute-form host changed");
        if (target.port != expected_port) fail("absolute-form port changed");
        if (target.consumed != 0) fail("absolute-form must keep the full header for relay");
}

int main(void) {
    char oversized[TAILNET_CONNECT_MAX_HEADER + 1];
    expect_target("CONNECT host.tailnet.ts.net:443 HTTP/1.1\r\n\r\n",
            "host.tailnet.ts.net:443", "host.tailnet.ts.net", 443);
    expect_target("CONNECT 100.64.0.10:8443 HTTP/1.1\r\nHost: 100.64.0.10\r\n\r\n",
            "100.64.0.10:8443", "100.64.0.10", 8443);
    expect_target("CONNECT [fd7a:115c:a1e0::1]:443 HTTP/1.1\r\n\r\n",
            "[fd7a:115c:a1e0::1]:443", "fd7a:115c:a1e0::1", 443);

    /* Chromium sends plain-HTTP navigations to an HTTP proxy as absolute-form targets. */
    expect_absolute("GET http://nas.tailad3199.ts.net:4533/path HTTP/1.1\r\n"
            "Host: nas.tailad3199.ts.net:4533\r\n\r\n",
            "nas.tailad3199.ts.net:4533", "nas.tailad3199.ts.net", 4533);
    expect_absolute("POST https://host.ts.net:8443/path?q=1 HTTP/1.1\r\n\r\n",
            "host.ts.net:8443", "host.ts.net", 8443);
    expect_absolute("GET http://100.64.0.10:8080 HTTP/1.1\r\n\r\n",
            "100.64.0.10:8080", "100.64.0.10", 8080);

    expect_result("GET host:443 HTTP/1.1\r\n\r\n", 25,
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
    expect_result("GET /relative/only HTTP/1.1\r\nHost: host\r\n\r\n", 43,
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
    expect_result("CONNECT host HTTP/1.1\r\n\r\n", 25,
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("CONNECT host:0 HTTP/1.1\r\n\r\n", 27,
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("CONNECT host:65536 HTTP/1.1\r\n\r\n", 31,
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("CONNECT host:443 HTTP/1.0\r\n\r\n", 29,
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("GET http://host:noport/ HTTP/1.1\r\n\r\n", 36,
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
    expect_result("CONNECT host:443 HTTP/1.1\n\n", 27,
            TAILNET_CONNECT_INCOMPLETE);
    expect_result("CONNECT host:443 HTTP/1.1\r\nX-Test: \x01\r\n\r\n", 41,
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("CONNECT host:443 HTTP/1.1\r\n", 27,
            TAILNET_CONNECT_INCOMPLETE);

    memset(oversized, 'A', sizeof(oversized));
        expect_result(oversized, sizeof(oversized), TAILNET_CONNECT_TOO_LARGE);
        puts("tailnet_connect_parser_self_test passed");
        return 0;
}
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../../app/src/main/jni/tailnet_connect_parser.h"

static void fail(const char *message) {
    fprintf(stderr, "tailnet_connect_parser_self_test: %s\n", message);
    exit(1);
}

static void expect_target(const char *request, const char *expected) {
    tailnet_connect_target target;
    tailnet_connect_result result = tailnet_parse_connect_request(
            request, strlen(request), &target);
    if (result != TAILNET_CONNECT_OK) fail("valid CONNECT request rejected");
    if (strcmp(target.address, expected) != 0) fail("CONNECT target changed");
    if (target.consumed != strlen(request)) fail("header boundary was not reported");
}

static void expect_result(const char *request, size_t length,
        tailnet_connect_result expected) {
    tailnet_connect_target target;
    tailnet_connect_result result = tailnet_parse_connect_request(request, length, &target);
    if (result != expected) fail("unexpected parser result");
    if (target.address[0] != '\0' || target.consumed != 0)
        fail("rejected request exposed a target");
}

static void expect_absolute(const char *request, const char *expected) {
    tailnet_connect_target target;
    tailnet_connect_result result = tailnet_parse_connect_request(
        static void expect_target(const char *request, const char *expected,
                const char *expected_host, unsigned short expected_port) {
    if (result != TAILNET_CONNECT_ABSOLUTE_FORM) fail("absolute-form request rejected");
    if (strcmp(target.address, expected) != 0) fail("absolute-form target changed");
    if (target.consumed != 0) fail("absolute-form must keep the full header for relay");
}

            if (strcmp(target.host, expected_host) != 0) fail("CONNECT host changed");
            if (target.port != expected_port) fail("CONNECT port changed");
int main(void) {
    char oversized[TAILNET_CONNECT_MAX_HEADER + 1];
    expect_target("CONNECT host.tailnet.ts.net:443 HTTP/1.1\r\n\r\n",
            "host.tailnet.ts.net:443");
    expect_target("CONNECT 100.64.0.10:8443 HTTP/1.1\r\nHost: 100.64.0.10\r\n\r\n",
            "100.64.0.10:8443");
    expect_target("CONNECT [fd7a:115c:a1e0::1]:443 HTTP/1.1\r\n\r\n",
            "[fd7a:115c:a1e0::1]:443");

    /* Chromium sends plain-HTTP navigations to an HTTP proxy as absolute-form
       request targets; the adapter must tunnel them through the Tailnet node. */
        static void expect_absolute(const char *request, const char *expected,
                const char *expected_host, unsigned short expected_port) {
            "Host: nas.tailad3199.ts.net:4533\r\n\r\n",
            "nas.tailad3199.ts.net:4533");
    expect_absolute("POST https://host.ts.net:8443/path?q=1 HTTP/1.1\r\n\r\n",
            "host.ts.net:8443");
    expect_absolute("GET http://100.64.0.10:8080 HTTP/1.1\r\n\r\n", "100.64.0.10:8080");
            if (strcmp(target.host, expected_host) != 0) fail("absolute-form host changed");
            if (target.port != expected_port) fail("absolute-form port changed");

    expect_result("GET host:443 HTTP/1.1\r\n\r\n", 25,
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
    expect_result("GET /relative/only HTTP/1.1\r\nHost: host\r\n\r\n", 43,
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
                    "host.tailnet.ts.net:443", "host.tailnet.ts.net", 443);
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
                    "100.64.0.10:8443", "100.64.0.10", 8443);
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
                    "[fd7a:115c:a1e0::1]:443", "fd7a:115c:a1e0::1", 443);
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("CONNECT host:0 HTTP/1.1\r\n\r\n", 27,
                    "nas.tailad3199.ts.net:4533", "nas.tailad3199.ts.net", 4533);
    expect_result("CONNECT host:65536 HTTP/1.1\r\n\r\n", 31,
                    "host.ts.net:8443", "host.ts.net", 8443);
            expect_absolute("GET http://100.64.0.10:8080 HTTP/1.1\r\n\r\n",
                    "100.64.0.10:8080", "100.64.0.10", 8080);
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("CONNECT host:443 HTTP/1.0\r\n\r\n", 29,
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("GET http://host:noport/ HTTP/1.1\r\n\r\n", 36,
            TAILNET_CONNECT_METHOD_NOT_ALLOWED);
    expect_result("CONNECT host:443 HTTP/1.1\n\n", 27,
            TAILNET_CONNECT_INCOMPLETE);
    expect_result("CONNECT host:443 HTTP/1.1\r\nX-Test: \x01\r\n\r\n", 41,
            TAILNET_CONNECT_BAD_REQUEST);
    expect_result("CONNECT host:443 HTTP/1.1\r\n", 27,
            TAILNET_CONNECT_INCOMPLETE);

    memset(oversized, 'A', sizeof(oversized));
    expect_result(oversized, sizeof(oversized), TAILNET_CONNECT_TOO_LARGE);
    puts("tailnet_connect_parser_self_test passed");
    return 0;
}