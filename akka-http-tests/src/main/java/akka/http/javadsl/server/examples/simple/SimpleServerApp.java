/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

//#https-http-app

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.Parameter;
import akka.http.javadsl.server.values.Parameters;
import akka.http.javadsl.server.values.PathMatcher;
import akka.http.javadsl.server.values.PathMatchers;
import com.typesafe.config.ConfigFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class SimpleServerApp extends HttpApp {
  static Parameter<Integer> x = Parameters.intValue("x");
  static Parameter<Integer> y = Parameters.intValue("y");

  static PathMatcher<Integer> xSegment = PathMatchers.intValue();
  static PathMatcher<Integer> ySegment = PathMatchers.intValue();

  static RequestVal<String> bodyAsName = RequestVals.entityAs(Unmarshallers.String());

  public static RouteResult multiply(RequestContext ctx, int x, int y) {
    int result = x * y;
    return ctx.complete(String.format("%d * %d = %d", x, y, result));
  }

  public static CompletionStage<RouteResult> multiplyAsync(final RequestContext ctx, final int x, final int y) {
    return CompletableFuture.supplyAsync(() -> multiply(ctx, x, y), ctx.executionContext());
  }

  @Override
  public Route createRoute() {
    Handler addHandler = new Handler() {
      @Override
      public RouteResult apply(RequestContext ctx) {
        int xVal = x.get(ctx);
        int yVal = y.get(ctx);
        int result = xVal + yVal;
        return ctx.complete(String.format("%d + %d = %d", xVal, yVal, result));
      }
    };
    Handler2<Integer, Integer> subtractHandler = new Handler2<Integer, Integer>() {
      public RouteResult apply(RequestContext ctx, Integer xVal, Integer yVal) {
        int result = xVal - yVal;
        return ctx.complete(String.format("%d - %d = %d", xVal, yVal, result));
      }
    };
    Handler1<String> helloPostHandler =
      new Handler1<String>() {
        @Override
        public RouteResult apply(RequestContext ctx, String s) {
          return ctx.complete("Hello " + s + "!");
        }
      };
    return
      route(
        // matches the empty path
        pathSingleSlash().route(
          getFromResource("web/calculator.html")
        ),
        // matches paths like this: /add?x=42&y=23
        path("add").route(
          handleWith(addHandler, x, y)
        ),
        path("subtract").route(
          handleWith2(x, y, subtractHandler)
        ),
        // matches paths like this: /multiply/{x}/{y}
        path("multiply", xSegment, ySegment).route(
          // bind handler by reflection
          handleReflectively(SimpleServerApp.class, "multiply", xSegment, ySegment)
        ),
        path("multiplyAsync", xSegment, ySegment).route(
          // bind async handler by reflection
          handleReflectively(SimpleServerApp.class, "multiplyAsync", xSegment, ySegment)
        ),
        post(
          path("hello").route(
            handleWith1(bodyAsName, helloPostHandler)
          )
        )
      );
  }

  // ** STARTING THE SERVER ** //

  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create("SimpleServerApp");
    final Http http = Http.get(system);

    boolean useHttps = false; // pick value from anywhere
    useHttps(system, http, useHttps);

    new SimpleServerApp().bindRoute("localhost", 8080, system);

    System.out.println("Type RETURN to exit");
    System.in.read();
    system.terminate();
  }

  // ** CONFIGURING ADDITIONAL SETTINGS ** //

  public static void useHttps(ActorSystem system, Http http, boolean useHttps) {
    if (useHttps) {

      HttpsConnectionContext https = null;
      try {
        // initialise the keystore
        // !!! never put passwords into code !!!
        final char[] password = new char[]{'a', 'b', 'c', 'd', 'e', 'f'};

        final KeyStore ks = KeyStore.getInstance("PKCS12");
        final InputStream keystore = SimpleServerApp.class.getClassLoader().getResourceAsStream("httpsDemoKeys/keys/server.p12");
        if (keystore == null) {
          throw new RuntimeException("Keystore required!");
        }
        ks.load(keystore, password);

        final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
        keyManagerFactory.init(ks, password);

        final TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ks);

        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), tmf.getTrustManagers(), SecureRandom.getInstanceStrong());

        https = ConnectionContext.https(sslContext);

      } catch (NoSuchAlgorithmException | KeyManagementException e) {
        system.log().error("Exception while configuring HTTPS.", e);
      } catch (CertificateException | KeyStoreException | UnrecoverableKeyException | IOException e) {
        system.log().error("Exception while ", e);
      }

      http.setDefaultServerHttpContext(https);
    }
  }

}
//#