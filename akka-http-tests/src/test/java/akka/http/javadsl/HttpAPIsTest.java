/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.event.LoggingAdapter;
import akka.http.javadsl.*;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.settings.ConnectionPoolSettings;
import akka.japi.Function;
import akka.stream.javadsl.Flow;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.ConnectHttp.toHost;
import static akka.http.javadsl.ConnectHttp.toHostHttps;

@SuppressWarnings("ConstantConditions")
public class HttpAPIsTest extends JUnitRouteTest {

  @Test
  public void placeholderCompileTimeOnlyTest() {
    // fails if there are no test cases
  }

  @SuppressWarnings("unused")
  public void compileOnly() throws Exception {
    final Http http = Http.get(system());

    final ConnectionContext connectionContext = ConnectionContext.https(SSLContext.getDefault());
    final HttpConnectionContext httpContext = ConnectionContext.noEncryption();
    final HttpsConnectionContext httpsContext = ConnectionContext.https(SSLContext.getDefault());

    String host = "";
    int port = 9090;
    ConnectionPoolSettings conSettings = null;
    LoggingAdapter log = null;

    http.bind(toHost("127.0.0.1", 8080), materializer());
    http.bind(toHost("127.0.0.1", 8080), materializer());
    http.bind(toHostHttps("127.0.0.1", 8080), materializer());

    final Flow<HttpRequest, HttpResponse, ?> handler = null;
    http.bindAndHandle(handler, toHost("127.0.0.1", 8080), materializer());
    http.bindAndHandle(handler, toHost("127.0.0.1", 8080), materializer());
    http.bindAndHandle(handler, toHostHttps("127.0.0.1", 8080).withCustomHttpsContext(httpsContext), materializer());

    final Function<HttpRequest, CompletionStage<HttpResponse>> handler1 = null;
    http.bindAndHandleAsync(handler1, toHost("127.0.0.1", 8080), materializer());
    http.bindAndHandleAsync(handler1, toHostHttps("127.0.0.1", 8080), materializer());

    final Function<HttpRequest, HttpResponse> handler2 = null;
    http.bindAndHandleSync(handler2, toHost("127.0.0.1", 8080), materializer());
    http.bindAndHandleSync(handler2, toHostHttps("127.0.0.1", 8080), materializer());

    final HttpRequest handler3 = null;
    http.singleRequest(handler3, materializer());
    http.singleRequest(handler3, httpsContext, materializer());
    http.singleRequest(handler3, httpsContext, conSettings, log, materializer());

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
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultHttpsContext());
    http.outgoingConnection(toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultHttpsContext());

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

    final ConnectWithHttps connect = toHostHttps("akka.io", 8081).withCustomHttpsContext(httpsContext).withDefaultHttpsContext();
    connect.effectiveHttpsConnectionContext(http.defaultClientHttpsContext()); // usage by us internally
  }

  @SuppressWarnings("unused")
  public void compileOnlyBinding() throws Exception {
    final Http http = Http.get(system());
    final HttpsConnectionContext httpsConnectionContext = null;

    http.bind(toHost("127.0.0.1"), materializer()); // 80
    http.bind(toHost("127.0.0.1", 8080), materializer()); // 8080

    http.bind(toHost("https://127.0.0.1"), materializer()); // HTTPS 443
    http.bind(toHost("https://127.0.0.1", 9090), materializer()); // HTTPS 9090

    http.bind(toHostHttps("127.0.0.1"), materializer()); // HTTPS 443
    http.bind(toHostHttps("127.0.0.1").withCustomHttpsContext(httpsConnectionContext), materializer()); // custom HTTPS 443

    http.bind(toHostHttps("http://127.0.0.1"), materializer()); // throws
  }
}