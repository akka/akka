# Test the client side

Start up the testsuite with docker:

```
docker run -ti --rm=true -p 8080:8080 -p 9001:9001 jrudolph/autobahn-testsuite
```

Then in sbt, to run all tests, use

```
akka-http-core-experimental/test:run-main akka.http.impl.engine.ws.WSClientAutobahnTest
```

or, to run a single test, use

```
akka-http-core-experimental/test:run-main akka.http.impl.engine.ws.WSClientAutobahnTest 1.1.1
```

After a run, you can access the results of the run at http://localhost:8080/cwd/reports/clients/index.html.

You can supply a configuration file for autobahn by mounting a version of `fuzzingserver.json` to `/tmp/fuzzingserver.json`
of the container, e.g. using this docker option:

```
-v /fullpath-on-host/my-fuzzingserver-config.json:/tmp/fuzzingserver.json
```

# Test the server side

Start up the test server in sbt:

```
akka-http-core-experimental/test:run-main akka.http.impl.engine.ws.WSServerAutobahnTest
```

Then, run the test suite with docker:

```
docker run -ti --rm=true -v `pwd`/reports:/tmp/server-report jrudolph/autobahn-testsuite-client
```

This will put the result report into a `reports` directory in the current working directory on the host.

You can supply a configuration file for autobahn by mounting a version of `fuzzingclient.json` to `/tmp/fuzzingclient.json`
of the container, e.g. using this docker option:

```
-v /fullpath-on-host/my-fuzzingclient-config.json:/tmp/fuzzingclient.json
```
