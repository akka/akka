/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.client;

import akka.event.LoggingAdapter;
import akka.http.ConnectionPoolSettings;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.testkit.JUnitRouteTest;

import javax.net.ssl.SSLContext;

import static akka.http.javadsl.ConnectHttp.*;
import static akka.http.javadsl.ConnectHttp.toHostHttps;

@SuppressWarnings("ConstantConditions")
public class HttpAPIsTest extends JUnitRouteTest {

  public void compileOnly() throws Exception {
    final Http http = Http.get(system());

    final HttpsConnectionContext httpsContext = ConnectionContext.https(SSLContext.getDefault());

    String host = "";
    int port = 9090;
    ConnectionPoolSettings conSettings = null;
    LoggingAdapter log = null;

    http.bind("127.0.0.1", 8080, materializer());
    http.bind("127.0.0.1", 8080, httpsContext, materializer());

    http.bindAndHandle(null, "127.0.0.1", 8080, materializer());
    http.bindAndHandle(null, "127.0.0.1", 8080, httpsContext, materializer());

    http.bindAndHandleAsync(null, "127.0.0.1", 8080, materializer());
    http.bindAndHandleAsync(null, "127.0.0.1", 8080, httpsContext, materializer());

    http.bindAndHandleSync(null, "127.0.0.1", 8080, materializer());
    http.bindAndHandleSync(null, "127.0.0.1", 8080, httpsContext, materializer());

    http.singleRequest(null, materializer());
    http.singleRequest(null, httpsContext, materializer());
    http.singleRequest(null, httpsContext, conSettings, log, materializer());

    http.outgoingConnection("akka.io");
    http.outgoingConnection("akka.io:8080");
    http.outgoingConnection("https://akka.io");
    http.outgoingConnection("https://akka.io:8081");

    http.outgoingConnection(toHost("akka.io"));
    http.outgoingConnection(toHost("akka.io", 8080));
    http.outgoingConnection(toHost("https://akka.io"));
    http.outgoingConnection(toHostHttps("akka.io")); // default ssl context (ssl-config)
    http.outgoingConnection(toHostHttps("ssh://akka.io")); // throws, we explicitly require https or ""
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext));
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultContext());
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultContext());

    // in future we can add modify(context -> Context) to "keep ssl-config defaults, but tweak them in code)

    http.newHostConnectionPool("akka.io", materializer());
    http.newHostConnectionPool("https://akka.io", materializer());
    http.newHostConnectionPool("https://akka.io:8080", materializer());
    http.newHostConnectionPool(toHost("akka.io"), materializer());
    http.newHostConnectionPool(toHostHttps("ftp://akka.io"), materializer()); // throws, we explicitly require https or ""
    http.newHostConnectionPool(toHostHttps("https://akka.io:2222"), materializer());
    http.newHostConnectionPool(toHostHttps("akka.io"), materializer());
    http.newHostConnectionPool(toHost(""), conSettings, log, materializer());


    http.cachedHostConnectionPool("akka.io", materializer());
    http.cachedHostConnectionPool("https://akka.io", materializer());
    http.cachedHostConnectionPool("https://akka.io:8080", materializer());
    http.cachedHostConnectionPool(toHost("akka.io"), materializer());
    http.cachedHostConnectionPool(toHostHttps("smtp://akka.io"), materializer()); // throws, we explicitly require https or ""
    http.cachedHostConnectionPool(toHostHttps("https://akka.io:2222"), materializer());
    http.cachedHostConnectionPool(toHostHttps("akka.io"), materializer());
    http.cachedHostConnectionPool(toHost("akka.io"), conSettings, log, materializer());

    http.superPool(materializer());
    http.superPool(conSettings, log, materializer());
    http.superPool(conSettings, httpsContext, log, materializer());

    ConnectHttp.UsingHttps connect = toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultContext();
    connect.connectionContext().orElse(http.defaultClientHttpsContext()); // usage by us internally
  }
}