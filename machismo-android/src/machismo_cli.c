/*
 * machismo_cli.c - command-line wrapper (test executable). The loader itself
 * lives in machismo.c and is exported as machismo_main() (libmachismo.so).
 */
#include <stdio.h>
#include <unistd.h>
#include "machismo_lib.h"

int main(int argc, char** argv)
{
	int rc = machismo_main(argc, argv);
	/* Upstream behaviour: leave via _exit() so library atexit/static
	 * destructors never run against torn-down guest state. */
	fflush(NULL);
	_exit(rc);
}
