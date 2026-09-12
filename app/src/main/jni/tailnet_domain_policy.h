#ifndef MEDIAN_TAILNET_DOMAIN_POLICY_H
#define MEDIAN_TAILNET_DOMAIN_POLICY_H

#include <stddef.h>

typedef struct tailnet_domain_policy tailnet_domain_policy;

tailnet_domain_policy *tailnet_domain_policy_create(
        const char *const *rules, size_t rule_count);
void tailnet_domain_policy_destroy(tailnet_domain_policy *policy);
int tailnet_domain_policy_matches(
        const tailnet_domain_policy *policy, const char *host);

#endif