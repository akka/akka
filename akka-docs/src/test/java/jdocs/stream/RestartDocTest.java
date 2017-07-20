/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.stream;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.KillSwitch;
import akka.stream.KillSwitches;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import scala.concurrent.duration.Duration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class RestartDocTest {

  static ActorSystem system;
  static Materializer materializer;

  // Mocking akka-http
  public static class Http {
    public static Http get(ActorSystem system) {
      return new Http();
    }
    public CompletionStage<Http> singleRequest(String uri) {
      return new CompletableFuture<>();
    }
    public NotUsed entity() {
      return NotUsed.getInstance();
    }
  }
  public static class HttpRequest {
    public static String create(String uri) {
      return uri;
    }
  }
  public static class ServerSentEvent {}
  public static class EventStreamUnmarshalling {
    public static EventStreamUnmarshalling fromEventStream() {
      return new EventStreamUnmarshalling();
    }
    public CompletionStage<Source<ServerSentEvent, NotUsed>> unmarshall(Http http, Materializer mat) {
      return new CompletableFuture<>();
    }
  }
  public void doSomethingElse() {

  }

  public void recoverWithBackoffSource() {
    //#restart-with-backoff-source
    Source<ServerSentEvent, NotUsed> eventStream = RestartSource.withBackoff(
        Duration.apply(3, TimeUnit.SECONDS), // min backoff
        Duration.apply(30, TimeUnit.SECONDS), // max backoff
        0.2, // adds 20% "noise" to vary the intervals slightly

        () ->
            // Create a source from a future of a source
            Source.fromSourceCompletionStage(
                // Issue a GET request on the event stream
                Http.get(system).singleRequest(HttpRequest.create("http://example.com/eventstream"))
                    .thenCompose(response ->
                        // Unmarshall it to a stream of ServerSentEvents
                        EventStreamUnmarshalling.fromEventStream()
                            .unmarshall(response, materializer)
                    )
            )
    );
    //#restart-with-backoff-source

    //#with-kill-switch
    KillSwitch killSwitch = eventStream
        .viaMat(KillSwitches.single(), Keep.right())
        .toMat(Sink.foreach(event -> System.out.println("Got event: " + event)), Keep.left())
        .run(materializer);

    doSomethingElse();

    killSwitch.shutdown();
    //#with-kill-switch

  }
}