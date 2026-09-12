#ifndef MEDIAN_TAILNET_CONNECT_ADAPTER_H
#define MEDIAN_TAILNET_CONNECT_ADAPTER_H

#include <stddef.h>

#include "tailscale.h"

typedef struct tailnet_connect_adapter tailnet_connect_adapter;

tailnet_connect_adapter *tailnet_connect_adapter_start(tailscale node);
int tailnet_connect_adapter_address(
        tailnet_connect_adapter *adapter, char *buffer, size_t buffer_size);
void tailnet_connect_adapter_stop(tailnet_connect_adapter *adapter);
tailscale tailnet_connect_adapter_node(tailnet_connect_adapter *adapter);

#endif