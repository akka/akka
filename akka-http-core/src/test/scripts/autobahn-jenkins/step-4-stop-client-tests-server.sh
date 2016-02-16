#!/usr/bin/env bash
# STOP TEST SERVER (CLIENT TESTS)
docker stop $(cat autobahn-client.cid)
rm -f autobahn-client.cid