set -e
bash /mnt/c/Claude/mina-port/android/compat/build.sh > /tmp/compat.log 2>&1 || (tail -5 /tmp/compat.log; exit 1)
bash /mnt/c/Claude/mina-quick.sh 2>&1 | tail -1
