/* Who is the peer: loopback, USB tethering, or the link a request came over. */
#include <strings.h>
#include "net_int.h"

bool peer_is_loopback(int fd) {
    struct sockaddr_storage ss;
    socklen_t l = sizeof ss;
    if(getpeername(fd, (struct sockaddr*)&ss, &l) != 0) return false;
    if(ss.ss_family == AF_INET) {
        const struct sockaddr_in* a = (const struct sockaddr_in*)&ss;
        return (ntohl(a->sin_addr.s_addr) >> 24) == 127;
    }
    if(ss.ss_family == AF_INET6) {
        const struct sockaddr_in6* a = (const struct sockaddr_in6*)&ss;
        if(IN6_IS_ADDR_LOOPBACK(&a->sin6_addr)) return true;
        return IN6_IS_ADDR_V4MAPPED(&a->sin6_addr) && a->sin6_addr.s6_addr[12] == 127;
    }
    return false;
}

/* Did the connection come in on a USB tethering interface? (by the local address it reached) */
bool local_is_usb(int fd) {
    struct sockaddr_in me;
    socklen_t l = sizeof me;
    if(getsockname(fd, (struct sockaddr*)&me, &l) != 0 || me.sin_family != AF_INET) return false;
    struct ifaddrs* list;
    if(getifaddrs(&list) != 0) return false;
    bool usb = false;
    for(struct ifaddrs* i = list; i; i = i->ifa_next) {
        if(!i->ifa_addr || i->ifa_addr->sa_family != AF_INET || !i->ifa_name) continue;
        if(((struct sockaddr_in*)i->ifa_addr)->sin_addr.s_addr != me.sin_addr.s_addr) continue;
        const char* n = i->ifa_name;
        usb = !strncmp(n, "rndis", 5) || !strncmp(n, "ncm", 3) || !strncmp(n, "usb", 3);
        break;
    }
    freeifaddrs(list);
    return usb;
}

const char* via_name(uint8_t v) {
    static const char* const names[] = {"", "phone", "usb", "tether", "wifi"};
    return names[v < 5 ? v : 0];
}

/* Which link a request came over. The PC proxy (pc/gitphin-usb) marks requests it carries over
 * the USB cable with "X-Gitphin-Via: usb"; on loopback that is the only way to tell them from
 * the phone's own WebView. The header counts only on loopback. */
uint8_t via_of(const Conn* c) {
    if(c->tether) return PORT_VIA_TETHER;
    if(!c->trusted) return PORT_VIA_WIFI;
    for(const char* line = strstr((const char*)c->buf, "\r\n"); line && line[2];) {
        line += 2;
        if(!strncasecmp(line, "x-gitphin-via:", 14)) {
            const char* v = line + 14;
            while(*v == ' ')
                v++;
            if(!strncasecmp(v, "usb", 3)) return PORT_VIA_USB;
        }
        if(line[0] == '\r') break;
        line = strstr(line, "\r\n");
    }
    return PORT_VIA_PHONE;
}
