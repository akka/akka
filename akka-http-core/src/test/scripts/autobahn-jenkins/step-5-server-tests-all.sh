#!/usr/bin/env bash
### SERVER TESTS ###

## RUN AKKA WS-ECHO SERVER ##
# sadly hardcoded here, we must do much more than just "run sbt", thus running manually
SBT_JAR=/usr/share/sbt-launcher-packaging/bin/sbt-launch.jar # this jar is "sbt-latest-deb" (from the apt package)
SBT_OPTS="-Dsbt.ivy.home=/localhome/jenkinsakka/.ivy2 -Dsbt.override.repos=false"

# warning, bash magic – the parens are important, they cause the background process to be executed in a sub shell
# and only thanks to that, will it not be forced into [Stopped] state, as bash thinks sbt is awaiting on some input
# and then decides to stop it (instead of keep it running). Keeping it in a sub shell, keeps it running.
TEST_CLASS=akka.http.impl.engine.ws.WSServerAutobahnTest
(java $SBT_OPTS -Dakka.ws-mode=sleep -Dakka.ws-host=127.0.0.1 -jar $SBT_JAR "akka-http-core-experimental/test:run-main $TEST_CLASS" | tee output &)

# because of the sub-shell $! is not reported, we need to find the PID some other way:
SUB_PID=$(jps -mlV | grep $TEST_CLASS | awk '{print $1}') # the PID of JVM


## PREPARE TCK ##
echo "~~~ Docker state before running SERVER tests ~~~"
listeners=docker ps | grep '8080->8080' | wc -l
if [ "$listeners" == "0" ]; then
  echo "Docker state OK..."
else
  for id in $(docker ps -q); do
    docker kill $id;
  done
fi


## AWAIT ON SERVER INIT ##
# we need to wait for the server to start (compilation may happen etc, it may take time)
set +x
echo "Awaiting server startup before running tests"
while [ "$(grep 'akka.http.impl.engine.ws.WSServerAutobahnTest' output | wc -l)" == "0" ];
do
  sleep 5
  echo -n '.';
done
set -x

# we need to configure the test-client to hit 127.0.0.1 (this works, even from within the container, see below)
echo '{
  "outdir": "/tmp/server-report",
  "servers": [
    {
      "agent": "AutobahnPython",
      "url": "ws://127.0.0.1:9001"
    }
  ],
  "cases": ["*"],
  "exclude-cases": [],
  "exclude-agent-cases": {}
}' > /tmp/fuzzingclient.json

## RUN TESTS ##
# net=host allows treating localhost in the container as-if the "hosts"
docker run --net=host \
           --rm=true \
           -v /tmp/fuzzingclient.json:/tmp/fuzzingclient.json \
           -v `pwd`/server-reports:/tmp/server-report \
           jrudolph/autobahn-testsuite-client

# reports are automatically put to `pwd`/reports, we expose them in jenkins

# okey, time to shut down the server
kill -9 $SUB_PID