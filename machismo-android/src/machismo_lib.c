/*
 * machismo_lib.c - helpers for the embedding API (machismo_lib.h).
 */
#include "machismo_lib.h"
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>

struct bigstack_args {
	int argc;
	char** argv;
	int rc;
};

static void* bigstack_thread(void* p)
{
	struct bigstack_args* a = p;
	a->rc = machismo_main(a->argc, a->argv);
	return NULL;
}

int machismo_main_bigstack(int argc, char** argv, size_t stack_bytes)
{
	struct bigstack_args a = { argc, argv, -1 };
	pthread_attr_t attr;
	pthread_t th;

	if (!stack_bytes)
		stack_bytes = 64UL * 1024 * 1024;
	pthread_attr_init(&attr);
	if (pthread_attr_setstacksize(&attr, stack_bytes) != 0 ||
	    pthread_create(&th, &attr, bigstack_thread, &a) != 0) {
		fprintf(stderr, "machismo: cannot create %zu-byte stack thread\n", stack_bytes);
		pthread_attr_destroy(&attr);
		return -1;
	}
	pthread_attr_destroy(&attr);
	pthread_join(th, NULL);
	return a.rc;
}
