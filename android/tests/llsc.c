#include <pthread.h>
#include <stdio.h>
#include <stdint.h>
static volatile uint32_t cnt, cas_cnt;
static void inc(volatile uint32_t* p){ uint32_t t,s; do { __asm__ volatile("ldxr %w0,[%2]\n add %w0,%w0,#1\n stxr %w1,%w0,[%2]":"=&r"(t),"=&r"(s):"r"(p):"memory"); } while(s); }
static void cas_inc(volatile uint32_t* p){ for(;;){ uint32_t old=*p, got, st; __asm__ volatile("1: ldaxr %w0,[%2]\n cmp %w0,%w3\n b.ne 2f\n stlxr %w1,%w4,[%2]\n cbnz %w1,1b\n 2:":"=&r"(got),"=&r"(st):"r"(p),"r"(old),"r"(old+1):"memory","cc"); if(got==old) return; } }
static void* w(void* a){ for(int i=0;i<1000000;i++){ inc(&cnt); cas_inc(&cas_cnt);} return 0; }
int main(){ pthread_t t[4]; for(int i=0;i<4;i++) pthread_create(&t[i],0,w,0); for(int i=0;i<4;i++) pthread_join(t[i],0); printf("llsc cnt=%u (expect 4000000) cas=%u (expect 4000000)\n",cnt,cas_cnt); return 0; }
