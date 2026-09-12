#include <stdio.h>
#include <stdlib.h>

#include "../../app/src/main/jni/tailnet_domain_policy.h"

static void check(int condition, const char *message) {
    if (!condition) {
        fprintf(stderr, "tailnet_domain_policy_self_test: %s\n", message);
        exit(1);
    }
}

int main(void) {
    const char *rules[] = {"example.internal", "EXAMPLE.INTERNAL."};
    tailnet_domain_policy *policy = tailnet_domain_policy_create(rules, 2);
    check(policy != NULL, "policy creation failed");
    check(tailnet_domain_policy_matches(policy, "ts.net"), "default rule missing");
    check(tailnet_domain_policy_matches(policy, "node.tail123.ts.net"), "Tailnet suffix missed");
    check(tailnet_domain_policy_matches(policy, "API.EXAMPLE.INTERNAL."), "custom suffix missed");
    check(!tailnet_domain_policy_matches(policy, "evilts.net"), "suffix confusion accepted");
    check(!tailnet_domain_policy_matches(policy, "ts.net.example.com"), "parent confusion accepted");
    check(!tailnet_domain_policy_matches(policy, "example.internal.attacker"), "custom parent confusion accepted");
    check(!tailnet_domain_policy_matches(policy, "example.internal:443"), "authority accepted as host");
    tailnet_domain_policy_destroy(policy);
    puts("tailnet_domain_policy_self_test passed");
    return 0;
}