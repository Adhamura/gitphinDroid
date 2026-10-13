/* HTTP Basic auth for connections that are not loopback or USB. */
#include <strings.h>
#include "net_int.h"

static int b64v(char c) {
    if(c >= 'A' && c <= 'Z') return c - 'A';
    if(c >= 'a' && c <= 'z') return c - 'a' + 26;
    if(c >= '0' && c <= '9') return c - '0' + 52;
    if(c == '+' || c == '-') return 62;
    if(c == '/' || c == '_') return 63;
    return -1;
}

static void b64dec(const char* s, size_t n, char* out, size_t cap) {
    uint32_t acc = 0;
    int bits = 0;
    size_t o = 0;
    for(size_t i = 0; i < n && s[i] != '='; i++) {
        int v = b64v(s[i]);
        if(v < 0) break;
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if(bits >= 8) {
            bits -= 8;
            if(o + 1 < cap) out[o++] = (char)((acc >> bits) & 0xFF);
        }
    }
    out[o] = '\0';
}

/* Equal strings, in a time that does not depend on where they differ. */
static bool same(const char* a, const char* b) {
    size_t la = strlen(a), lb = strlen(b);
    unsigned d = (unsigned)(la ^ lb);
    for(size_t i = 0; i < la; i++)
        d |= (unsigned)(a[i] ^ b[lb ? i % lb : 0]);
    return d == 0;
}

/* The head carries "Authorization: Basic base64(user:password)" with our user (any when ours is
 * "") and password? */
bool head_auth_ok(const char* h, const char* user, const char* pass) {
    for(const char* line = h; line && *line;) {
        const char* eol = strstr(line, "\r\n");
        size_t len = eol ? (size_t)(eol - line) : strlen(line);
        if(len == 0) break;
        if(len > 14 && !strncasecmp(line, "authorization:", 14)) {
            const char* v = line + 14;
            const char* end = line + len;
            while(v < end && *v == ' ')
                v++;
            if(end - v > 6 && !strncasecmp(v, "basic ", 6)) {
                v += 6;
                while(v < end && *v == ' ')
                    v++;
                char cred[256];
                b64dec(v, (size_t)(end - v), cred, sizeof cred);
                char* colon = strchr(cred, ':');
                if(!colon) return false;
                *colon = '\0';
                bool user_ok = !user[0] || same(cred, user);
                bool pass_ok = same(colon + 1, pass);
                return user_ok & pass_ok;
            }
            return false;
        }
        line = eol ? eol + 2 : NULL;
    }
    return false;
}
