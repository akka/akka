/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.sourceorflow;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.pattern.Patterns;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public class MapAsyncs {

  private final Random random = new Random();

  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  private final Source<Event, NotUsed> events =
      Source.fromIterator(() -> Stream.iterate(1, i -> i + 1).iterator())
          .throttle(1, Duration.ofMillis(50))
          .map(Event::new);
  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  private final ActorSystem system = ActorSystem.create("mapAsync-operator-examples");

  public MapAsyncs() {}

  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  public CompletionStage<Integer> eventHandler(Event in) throws InterruptedException {
    System.out.println("Processing event number " + in + "...");
    // ...
    // #mapasync-strict-order
    // #mapasync-concurrent
    // #mapasyncunordered
    CompletionStage<Integer> cs;
    if (random.nextInt(5) == 0) {
      cs =
          Patterns.after(
              Duration.ofMillis(500),
              system,
              () -> CompletableFuture.completedFuture(in.sequenceNumber));
    } else {
      cs = CompletableFuture.completedFuture(in.sequenceNumber);
    }
    return cs.thenApply(
        i -> {
          System.out.println("Completed processing " + i.intValue());
          return i;
        });
    // #mapasync-strict-order
    // #mapasync-concurrent
    // #mapasyncunordered
  }
  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  private void runStrictOrder() {
    // #mapasync-strict-order

    events
        .mapAsync(1, this::eventHandler)
        .map(in -> "`mapSync` emitted event number " + in.intValue())
        .runWith(Sink.foreach(str -> System.out.println(str)), system);
    // #mapasync-strict-order
  }

  private void run() {
    // #mapasync-concurrent

    events
        .mapAsync(10, this::eventHandler)
        .map(in -> "`mapSync` emitted event number " + in.intValue())
        .runWith(Sink.foreach(str -> System.out.println(str)), system);
    // #mapasync-concurrent
  }

  private void runUnordered() {
    // #mapasyncunordered

    events
        .mapAsyncUnordered(10, this::eventHandler)
        .map(in -> "`mapSync` emitted event number " + in.intValue())
        .runWith(Sink.foreach(str -> System.out.println(str)), system);
    // #mapasyncunordered
  }

  public static void main(String[] args) {
    new MapAsyncs().run();
  }

  static class Event {
    public final int sequenceNumber;

    public Event(int sequenceNumber) {
      this.sequenceNumber = sequenceNumber;
    }

    @Override
    public String toString() {
      return "Event(" + sequenceNumber + ')';
    }
  }
}
