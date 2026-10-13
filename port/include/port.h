#pragma once
/* gitphinDroid: the Gitphin core as a server process on Android (and Linux, for tests). */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "hub_transport.h"

typedef struct {
    const char* root; /* the folder that the core calls "/ext" */
    const char* bind; /* "127.0.0.1" (this device only) or "0.0.0.0" (also the network) */
    int port;
    /* Wi-Fi and other networks (loopback and USB tethering are always allowed, no sign-in):
     *   PORT_WIFI_OFF       refused (403)
     *   PORT_WIFI_PASSWORD  HTTP Basic with user and password ("" user = any user name)
     *   PORT_WIFI_OPEN      allowed, no sign-in */
    int wifi;
    const char* user;
    const char* password;
    /* Folder for the repos; NULL or "" = root/apps_data/gitphin/git. */
    const char* git_dir;
} PortConfig;

/* Opens the socket, then runs the worker thread. 0 or a negative errno. */
int port_server_start(const PortConfig* cfg);
/* Stops the worker (it finishes the request in progress) and closes the socket. */
void port_server_stop(void);
bool port_server_running(void);
/* {"running":..,"requests":..,"last":"..","activity":..,"bind":"..","port":..,"via":"phone|usb|tether|wifi|",
 * "prog":{..}} (port_server.c) */
void port_server_status(char* out, size_t cap);

void port_set_device(const char* name, const char* version, int battery_pct, bool charging);

/* TCP transport (port_transport.c). */
#define PORT_WIFI_OFF      0
#define PORT_WIFI_PASSWORD 1
#define PORT_WIFI_OPEN     2
HubTransport* port_transport_listen(const PortConfig* cfg, int* err);
/* Data for the /pc/info and /pc/wifi routes (the Collector tab): kind is "info" or "wifi".
 * Returns malloc'd JSON (the transport frees it) or NULL (404). Called from the acceptor thread. */
typedef char* (*PortCollector)(const char* kind);
void port_set_collector(PortCollector fn);

/* The link of the request the worker serves now (or served last). */
#define PORT_VIA_NONE   0
#define PORT_VIA_PHONE  1 /* the phone itself (loopback) */
#define PORT_VIA_USB    2 /* a PC over the USB cable, through pc/gitphin-usb (adb) */
#define PORT_VIA_TETHER 3 /* a PC over USB tethering */
#define PORT_VIA_WIFI   4 /* Wi-Fi or another network */
uint8_t port_transport_via(void);

/* Thread-safe: makes a running hub_transport_accept return at once. */
void port_transport_wake(HubTransport* t);

/* Webhooks (port_webhook.c): one JSON event per ref change (push, repo_created, repo_deleted).
 * Called from the worker thread; the sink must return fast (the app only queues it). */
typedef void (*PortWebhookSink)(const char* json);
void port_set_webhook_sink(PortWebhookSink fn);
