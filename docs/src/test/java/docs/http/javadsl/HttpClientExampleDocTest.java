/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.Done;
import akka.actor.AbstractActor;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.HostConnectionPool;
import akka.japi.Pair;

import akka.japi.pf.ReceiveBuilder;
import akka.stream.Materializer;
import akka.util.ByteString;
import scala.compat.java8.FutureConverters;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;
import akka.stream.javadsl.*;
import akka.http.javadsl.OutgoingConnection;
import akka.http.javadsl.Http;

import static akka.http.javadsl.ConnectHttp.toHost;
import static akka.pattern.PatternsCS.*;

import java.util.concurrent.CompletionStage;

//#manual-entity-consume-example-1
import java.io.File;
import akka.actor.ActorSystem;

import java.util.concurrent.TimeUnit;
import java.util.function.Function; 
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Framing;
import akka.http.javadsl.model.*;
import scala.concurrent.duration.FiniteDuration;
import scala.util.Try;
//#manual-entity-consume-example-1

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
            // The `outgoingConnection` API is very low-level. Use it only if you already have a `Source[HttpRequest]`
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
    final Materializer materializer = ActorMaterializer.create(system);

    final CompletionStage<HttpResponse> responseFuture =
      Http.get(system)
          .singleRequest(HttpRequest.create("http://akka.io"), materializer);
    //#single-request-example
  }

  static
      //#single-request-in-actor-example
    class Myself extends AbstractActor {
      final Http http = Http.get(context().system());
      final ExecutionContextExecutor dispatcher = context().dispatcher();
      final Materializer materializer = ActorMaterializer.create(context());

      public Myself() {
        receive(ReceiveBuilder
         .match(String.class, url -> {
           pipe(fetch (url), dispatcher).to(self());
         }).build());
      }

      CompletionStage<HttpResponse> fetch(String url) {
        return http.singleRequest(HttpRequest.create(url), materializer);
      }
    }
    //#single-request-in-actor-example

}
