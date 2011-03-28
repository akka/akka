#!/bin/sh

# flush rules
ipfw del pipe 1
ipfw del pipe 2
ipfw -q -f flush
ipfw -q -f pipe flush

if [ "$1" == "" ]; then
    echo "Options: ip-mod.sh slow"
    echo "         ip-mod.sh block"
    echo "         ip-mod.sh reset"
    echo "         ip-mod.sh restore"
    exit
elif [ "$1" == "restore" ]; then
    echo "restoring normal network"
    exit
elif [ "$1" == "slow" ]; then
    # simulate slow connection <to specific hosts>
    echo "enabling slow connection"
    ipfw add pipe 1 ip from any to any
    ipfw add pipe 2 ip from any to any
    ipfw pipe 1 config bw 60KByte/s delay 350ms
    ipfw pipe 2 config bw 60KByte/s delay 350ms
elif [ "$1" == "block" ]; then
    echo "enabling blocked connections"
    ipfw add 1 deny tcp from any to any 1024-65535
elif [ "$1" == "reset" ]; then
    echo "enabling reset connections"
    ipfw add 1 reset tcp from any to any 1024-65535
fi
