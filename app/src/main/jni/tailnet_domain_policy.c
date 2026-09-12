#include "tailnet_domain_policy.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#define TAILNET_DOMAIN_MAX_HOST 253

struct tailnet_domain_policy {
    size_t rule_count;
    char **rules;
};

static char *normalize_name(const char *source) {
    size_t length;
    char *value;
    size_t index;
    if (source == NULL) return NULL;
    length = strlen(source);
    while (length > 0 && source[length - 1] == '.') --length;
    if (length == 0 || length > TAILNET_DOMAIN_MAX_HOST || source[0] == '.') return NULL;
    value = malloc(length + 1);
    if (value == NULL) return NULL;
    for (index = 0; index < length; ++index) {
        unsigned char character = (unsigned char) source[index];
        if (!((character >= 'a' && character <= 'z') ||
                (character >= 'A' && character <= 'Z') ||
                (character >= '0' && character <= '9') ||
                character == '.' || character == '-')) {
            free(value);
            return NULL;
        }
        if (character == '.' && (index == 0 || source[index - 1] == '.')) {
            free(value);
            return NULL;
        }
        value[index] = (char) tolower(character);
    }
    value[length] = '\0';
    return value;
}

tailnet_domain_policy *tailnet_domain_policy_create(
        const char *const *rules, size_t rule_count) {
    tailnet_domain_policy *policy = calloc(1, sizeof(*policy));
    size_t index;
    if (policy == NULL) return NULL;
    policy->rules = calloc(rule_count + 1, sizeof(*policy->rules));
    if (policy->rules == NULL) goto fail;
    policy->rules[policy->rule_count] = normalize_name("ts.net");
    if (policy->rules[policy->rule_count++] == NULL) goto fail;
    for (index = 0; index < rule_count; ++index) {
        char *normalized = normalize_name(rules[index]);
        size_t current;
        if (normalized == NULL) goto fail;
        for (current = 0; current < policy->rule_count; ++current) {
            if (strcmp(policy->rules[current], normalized) == 0) break;
        }
        if (current == policy->rule_count) {
            policy->rules[policy->rule_count++] = normalized;
        } else {
            free(normalized);
        }
    }
    return policy;

fail:
    tailnet_domain_policy_destroy(policy);
    return NULL;
}

void tailnet_domain_policy_destroy(tailnet_domain_policy *policy) {
    size_t index;
    if (policy == NULL) return;
    for (index = 0; index < policy->rule_count; ++index) free(policy->rules[index]);
    free(policy->rules);
    free(policy);
}

int tailnet_domain_policy_matches(
        const tailnet_domain_policy *policy, const char *host) {
    char *normalized;
    size_t index;
    int matches = 0;
    if (policy == NULL) return 0;
    normalized = normalize_name(host);
    if (normalized == NULL) return 0;
    for (index = 0; index < policy->rule_count; ++index) {
        const char *rule = policy->rules[index];
        size_t host_length = strlen(normalized);
        size_t rule_length = strlen(rule);
        if ((host_length == rule_length && strcmp(normalized, rule) == 0) ||
                (host_length > rule_length &&
                normalized[host_length - rule_length - 1] == '.' &&
                strcmp(normalized + host_length - rule_length, rule) == 0)) {
            matches = 1;
            break;
        }
    }
    free(normalized);
    return matches;
}