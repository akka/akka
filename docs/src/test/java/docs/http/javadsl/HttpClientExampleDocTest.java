/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.Done;
import akka.actor.*;
import akka.http.javadsl.model.headers.HttpCredentials;
import akka.util.ByteString;
import scala.concurrent.ExecutionContextExecutor;
import akka.stream.javadsl.*;
import akka.http.javadsl.ClientTransport;
import akka.http.javadsl.settings.ConnectionPoolSettings;
import akka.http.javadsl.Http;
import akka.http.javadsl.OutgoingConnection;

import static akka.http.javadsl.ConnectHttp.toHost;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

//#manual-entity-consume-example-1
import java.io.File;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Framing;
import akka.http.javadsl.model.*;
import scala.concurrent.duration.FiniteDuration;
//#manual-entity-consume-example-1

//#collecting-headers-example
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import akka.http.javadsl.model.headers.SetCookie;
//#collecting-headers-example


//#single-request-in-actor-example
import akka.actor.AbstractActor;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import scala.concurrent.ExecutionContextExecutor;

import java.util.concurrent.CompletionStage;

import static akka.pattern.PatternsCS.pipe;

//#single-request-in-actor-example

@SuppressWarnings("unused")
public class HttpClientExampleDocTest {

  static HttpResponse responseFromSomewhere() {
    return null;
  }

  void manualEntityComsumeExample() {
    //#manual-entity-consume-example-1

    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final HttpResponse response = responseFromSomewhere();

    final Function<ByteString, ByteString> transformEachLine = line -> line /* some transformation here */;

    final int maximumFrameLength = 256;

    response.entity().getDataBytes()
      .via(Framing.delimiter(ByteString.fromString("\n"), maximumFrameLength, FramingTruncation.ALLOW))
      .map(transformEachLine::apply)
      .runWith(FileIO.toPath(new File("/tmp/example.out").toPath()), materializer);
    //#manual-entity-consume-example-1
  }

  private static class ConsumeExample2 {
    //#manual-entity-consume-example-2
    final class ExamplePerson {
      final String name;
      public ExamplePerson(String name) { this.name = name; }
    }

    public ExamplePerson parse(ByteString line) {
      return new ExamplePerson(line.utf8String());
    }

    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final HttpResponse response = responseFromSomewhere();

    // toStrict to enforce all data be loaded into memory from the connection
    final CompletionStage<HttpEntity.Strict> strictEntity = response.entity()
        .toStrict(FiniteDuration.create(3, TimeUnit.SECONDS).toMillis(), materializer);

    // while API remains the same to consume dataBytes, now they're in memory already:

    final CompletionStage<ExamplePerson> person =
      strictEntity
        .thenCompose(strict ->
          strict.getDataBytes()
            .runFold(ByteString.empty(), (acc, b) -> acc.concat(b), materializer)
            .thenApply(this::parse)
        );
    //#manual-entity-consume-example-2
  }

  void manualEntityDiscardExample1() {
    //#manual-entity-discard-example-1
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final HttpResponse response = responseFromSomewhere();

    final HttpMessage.DiscardedEntity discarded = response.discardEntityBytes(materializer);

    discarded.completionStage().whenComplete((done, ex) -> {
      System.out.println("Entity discarded completely!");
    });
    //#manual-entity-discard-example-1
  }

  void manualEntityDiscardExample2() {
    //#manual-entity-discard-example-2
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final HttpResponse response = responseFromSomewhere();

    final CompletionStage<Done> discardingComplete = response.entity().getDataBytes().runWith(Sink.ignore(), materializer);

    discardingComplete.whenComplete((done, ex) -> {
      System.out.println("Entity discarded completely!");
    });
    //#manual-entity-discard-example-2
  }


  // compile only test
  public void testConstructRequest() {
    //#outgoing-connection-example

    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final Flow<HttpRequest, HttpResponse, CompletionStage<OutgoingConnection>> connectionFlow =
            Http.get(system).outgoingConnection(toHost("akka.io", 80));
    final CompletionStage<HttpResponse> responseFuture =
            // This is actually a bad idea in general. Even if the `connectionFlow` was instantiated only once above,
            // a new connection is opened every single time, `runWith` is called. Materialization (the `runWith` call)
            // and opening up a new connection is slow.
            //
            // The `outgoingConnection` API is very low-level. Use it only if you already have a `Source[HttpRequest, _]`
            // (other than Source.single) available that you want to use to run requests on a single persistent HTTP
            // connection.
            //
            // Unfortunately, this case is so uncommon, that we couldn't come up with a good example.
            //
            // In almost all cases it is better to use the `Http().singleRequest()` API instead.
            Source.single(HttpRequest.create("/"))
                    .via(connectionFlow)
                    .runWith(Sink.<HttpResponse>head(), materializer);
    //#outgoing-connection-example
  }

  // compile only test
  public void testSingleRequestExample() {
    //#single-request-example
    final ActorSystem system = ActorSystem.create();

    final CompletionStage<HttpResponse> responseFuture =
      Http.get(system)
          .singleRequest(HttpRequest.create("http://akka.io"));
    //#single-request-example
  }

  // compile only test
  public void singleRequestInActorExample1() {
    //#single-request-in-actor-example
    class SingleRequestInActorExample extends AbstractActor {
      final Http http = Http.get(context().system());
      final ExecutionContextExecutor dispatcher = context().dispatcher();
      final Materializer materializer = ActorMaterializer.create(context());

      @Override
      public Receive createReceive() {
        return receiveBuilder()
          .match(String.class, url -> pipe(fetch(url), dispatcher).to(self()))
          .build();
      }

      CompletionStage<HttpResponse> fetch(String url) {
        return http.singleRequest(HttpRequest.create(url));
      }
    }
    //#single-request-in-actor-example
  }

  // compile only test
  public void testSingleRequestWithHttpsProxyExample() {
    //#https-proxy-example-single-request

    final ActorSystem system = ActorSystem.create();

    ClientTransport proxy = ClientTransport.httpsProxy(InetSocketAddress.createUnresolved("192.168.2.5", 8080));
    ConnectionPoolSettings poolSettingsWithHttpsProxy = ConnectionPoolSettings.create(system).withTransport(proxy);

    final CompletionStage<HttpResponse> responseFuture =
        Http.get(system)
            .singleRequest(
                  HttpRequest.create("https://github.com"),
                  Http.get(system).defaultClientHttpsContext(),
                  poolSettingsWithHttpsProxy, // <- pass in the custom settings here
                  system.log());

    //#https-proxy-example-single-request
  }

  // compile only test
  public void testSingleRequestWithHttpsProxyExampleWithAuth() {

    final ActorSystem system = ActorSystem.create();

    //#auth-https-proxy-example-single-request
    InetSocketAddress proxyAddress =
      InetSocketAddress.createUnresolved("192.168.2.5", 8080);
    HttpCredentials credentials =
      HttpCredentials.createBasicHttpCredentials("proxy-user", "secret-proxy-pass-dont-tell-anyone");

    ClientTransport proxy = ClientTransport.httpsProxy(proxyAddress, credentials); // include credentials
    ConnectionPoolSettings poolSettingsWithHttpsProxy = ConnectionPoolSettings.create(system).withTransport(proxy);

    final CompletionStage<HttpResponse> responseFuture =
        Http.get(system)
            .singleRequest(
                  HttpRequest.create("https://github.com"),
                  Http.get(system).defaultClientHttpsContext(),
                  poolSettingsWithHttpsProxy, // <- pass in the custom settings here
                  system.log());

    //#auth-https-proxy-example-single-request
  }

  // compile only test
  public void testCollectingHeadersExample() {

    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    //#collecting-headers-example
    final HttpResponse response = responseFromSomewhere();

    List<SetCookie> setCookies = StreamSupport.stream(response.getHeaders().spliterator(), false)
      .filter(SetCookie.class::isInstance)
      .map(SetCookie.class::cast)
      .collect(Collectors.toList());

    System.out.println("Cookies set by a server: " + setCookies);

    response.discardEntityBytes(materializer);
    //#collecting-headers-example
  }
}
