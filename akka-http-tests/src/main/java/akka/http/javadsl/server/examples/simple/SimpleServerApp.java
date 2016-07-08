/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

//#https-http-app

import akka.NotUsed;
import static akka.http.javadsl.server.PathMatchers.segment;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.*;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import static akka.http.javadsl.server.PathMatchers.integerSegment;
import static akka.http.javadsl.server.Unmarshaller.entityToString;

public class SimpleServerApp extends AllDirectives { // or import Directives.*

  public Route multiply(int x, int y) {
    int result = x * y;
    return complete(String.format("%d * %d = %d", x, y, result));
  }

  public CompletionStage<Route> multiplyAsync(Executor ctx, int x, int y) {
    return CompletableFuture.supplyAsync(() -> multiply(x, y), ctx);
  }

  public Route createRoute() {
    Route addHandler = parameter(StringUnmarshallers.INTEGER, "x", x ->
      parameter(StringUnmarshallers.INTEGER, "y", y -> {
        int result = x + y;
        return complete(String.format("%d + %d = %d", x, y, result));
      })
    );

    BiFunction<Integer, Integer, Route> subtractHandler = (x, y) -> {
      int result = x - y;
      return complete(String.format("%d - %d = %d", x, y, result));
    };

    return
      route(
        // matches the empty path
        pathSingleSlash(() ->
          getFromResource("web/calculator.html")
        ),
        // matches paths like this: /add?x=42&y=23
        path("add", () -> addHandler),
        path("subtract", () ->
          parameter(StringUnmarshallers.INTEGER, "x", x ->
            parameter(StringUnmarshallers.INTEGER, "y", y ->
              subtractHandler.apply(x, y)
            )
          )
        ),
        // matches paths like this: /multiply/{x}/{y}
        path(PathMatchers.segment("multiply").slash(integerSegment()).slash(integerSegment()),
          this::multiply
        ),
        path(PathMatchers.segment("multiplyAsync").slash(integerSegment()).slash(integerSegment()), (x, y) ->
          extractExecutionContext(ctx ->
            onSuccess(() -> multiplyAsync(ctx, x, y), Function.identity())
          )
        ),
        post(() ->
          path("hello", () ->
            entity(entityToString(), body ->
              complete("Hello " + body + "!")
            )
          )
        )
      );
  }

  // ** STARTING THE SERVER ** //

  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create("SimpleServerApp");
    final ActorMaterializer materializer = ActorMaterializer.create(system);
    final Http http = Http.get(system);

    boolean useHttps = false; // pick value from anywhere
    useHttps(system, http, useHttps);

    final SimpleServerApp app = new SimpleServerApp();
    final Flow<HttpRequest, HttpResponse, NotUsed> flow = app.createRoute().flow(system, materializer);
    
    Http.get(system).bindAndHandle(flow, ConnectHttp.toHost("localhost", 8080), materializer);

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
        sslContext.init(keyManagerFactory.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

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
