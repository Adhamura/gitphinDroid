#pragma once
/* Internal to port/net/: the transport state and the functions the files share. */
#include "port.h"

#include <furi.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define PEND_MAX         16
#define READY_MAX        16
#define CLOSE_MAX        32
#define PEND_IDLE_MS     20000 /* a spare connection that sends no full head in time is dropped */
#define CLOSE_DRAIN_MS   2000
#define HEAD_MAX         8192
#define WRITE_TIMEOUT_MS 30000
#define STUCK_S          180 /* /pc/hub: the page says "not answering" after this */

typedef struct {
    int fd;
    uint32_t t;
    bool trusted; /* loopback or USB */
    bool tether; /* came in on a USB tethering interface */
    uint8_t via; /* PORT_VIA_*, set when the head is in */
    size_t len;
    uint8_t* buf; /* HEAD_MAX + 1 */
} Conn;

struct HubTransport {
    int lfd;
    int ctl[2]; /* pipe to the acceptor: stop, or a closed fd was queued */
    int wifi; /* PORT_WIFI_* */
    char user[128];
    char pass[128];
    pthread_t acceptor;

    pthread_mutex_t mu;
    pthread_cond_t cv; /* ready queue changed, or stop */
    bool stopping;
    Conn ready[READY_MAX];
    int nready;
    int closed_q[CLOSE_MAX]; /* fds the worker finished, for the acceptor to drain */
    int nclosed_q;
    volatile uint32_t busy_since; /* tick when the worker took a connection, 0 = idle */
    volatile uint8_t via; /* link of the connection the worker serves (or served last) */

    /* acceptor only */
    Conn pend[PEND_MAX];
    int npend;
    int closing[CLOSE_MAX];
    uint32_t closing_t[CLOSE_MAX];
    int nclosing;

    /* worker only */
    int fd;
    bool peer_closed;
    uint8_t* pre;
    size_t pre_len, pre_pos;
};

/* ---- shared between the net/ files ---- */

extern volatile uint8_t net_last_via; /* transport.c */
extern PortCollector net_collector; /* pc_routes.c */

void net_set_nonblock(int fd); /* transport.c */
void net_ctl_poke(HubTransport* t);
bool net_wait_fd(int fd, short ev, uint32_t timeout_ms);
bool net_send_all(int fd, const void* data, size_t len, uint32_t timeout_ms);
void net_send_answer(int fd, int code, const char* reason, const char* ctype, const char* extra, const char* body);

bool head_auth_ok(const char* h, const char* user, const char* pass); /* auth.c */

bool peer_is_loopback(int fd); /* peer.c */
bool local_is_usb(int fd);
const char* via_name(uint8_t v);
uint8_t via_of(const Conn* c);

bool pc_local(const char* path); /* pc_routes.c */
void answer_pc(HubTransport* t, int fd, const char* path); /* pc_routes.c */

void* net_acceptor(void* arg); /* acceptor.c: the acceptor thread */
void conn_free(Conn* c);
