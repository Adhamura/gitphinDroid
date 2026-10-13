#include "hub_gitwrite.h"
#include "hub_sha1.h"

#include <stdlib.h>
#include <string.h>
#include "hub_mem.h"

/* One translation unit: the parts below are included here, in this order, so
 * the build is the same as one file (statics, inlining, merged strings).
 * See src/README.md. */
#include "hub_gitwrite_int.h"
#include "hub_gitwrite_obj.c"
#include "hub_gitwrite_tree.c"
