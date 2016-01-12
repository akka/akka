#!/usr/bin/env bash
### CLIENT TESTS ###

CLIENT_CID=autobahn-client.cid

echo "~~~ Docker state before running CLIENT tests ~~~"
listeners=docker ps | grep '8080->8080' | wc -l
if [ "$listeners" == "0" ]; then
  echo "Docker state OK..."
else
  for id in $(docker ps -q); do
    docker kill $id;
  done
fi

## RUN CLIENT-SIDE TEST SERVER ##
# TODO technically should be possible to -v mount the reports instead (unsure on directory though)
#      this would spare us step-3
rm -f $CLIENT_CID
docker run -d --cidfile=$CLIENT_CID \
           -p 8080:8080 -p 9001:9001 \
           jrudolph/autobahn-testsuite
