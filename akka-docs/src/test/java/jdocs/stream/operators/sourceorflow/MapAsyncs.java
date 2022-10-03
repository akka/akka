/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.sourceorflow;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.function.Function;
import akka.pattern.Patterns;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public class MapAsyncs {

  private final Random random = new Random();

  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  private final Source<Event, NotUsed> events = // ...
      // #mapasync-strict-order
      // #mapasync-concurrent
      // #mapasyncunordered
      Source.fromIterator(() -> Stream.iterate(1, i -> i + 1).iterator())
          .throttle(1, Duration.ofMillis(50))
          .map(PlainEvent::new);

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
              () -> CompletableFuture.completedFuture(in.sequenceNumber()));
    } else {
      cs = CompletableFuture.completedFuture(in.sequenceNumber());
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

  private void runPartitioned() {
    // #mapAsyncPartitioned
    Source<EntityEvent, NotUsed> eventsForEntities = // ...
        // #mapAsyncPartitioned
        Source.fromIterator(() -> Stream.iterate(1, i -> i + 1).iterator())
            .throttle(1, Duration.ofMillis(1))
            .statefulMapConcat(
                () -> {
                  final HashMap<Integer, Integer> countsPerEntity = new HashMap<Integer, Integer>();

                  return (n) -> {
                    int entityId;

                    if (random.nextBoolean() || countsPerEntity.isEmpty()) {
                      entityId = random.nextInt(n);
                    } else {
                      int m = random.nextInt(countsPerEntity.size()) + 1;
                      Iterator<Integer> keysIterator = countsPerEntity.keySet().iterator();

                      Integer selected = Integer.valueOf(-1);
                      int i = 0;
                      while (keysIterator.hasNext() && i < m) {
                        if (i == m - 1) {
                          selected = keysIterator.next();
                        } else {
                          keysIterator.next();
                        }
                        i++;
                      }
                      entityId = selected.intValue();
                    }

                    if (entityId < 0) {
                      throw new AssertionError("entityId should be non-negative, was " + entityId);
                    }

                    int seqNr =
                        countsPerEntity
                                .getOrDefault(Integer.valueOf(entityId), Integer.valueOf(0))
                                .intValue()
                            + 1;
                    countsPerEntity.put(Integer.valueOf(entityId), Integer.valueOf(seqNr));

                    return Collections.singletonList(new EntityEvent(entityId, seqNr));
                  };
                })
            .take(1000);

    // #mapAsyncPartitioned
    Function<EntityEvent, Integer> partitioner =
        (event) -> {
          Integer partition = Integer.valueOf(event.entityId);
          System.out.println("Assigned event " + event + " to partition " + event.entityId);
          return partition;
        };

    eventsForEntities
        // simulate a message broker with offset tracking
        .statefulMapConcat(
            () -> {
              final int[] offset = new int[1]; // trick to close over an int...
              offset[0] = 0;

              return (event) -> {
                System.out.println(
                    "Received event " + event + " at offset " + offset[0] + " from message broker");
                offset[0]++;
                return Collections.singletonList(event);
              };
            })
        .mapAsyncPartitioned(
            10,  // parallelism
            1,   // perPartition
            partitioner,
            (event, partition) -> {
              System.out.println("Processing event " + event + " from partition " + partition);

              CompletionStage<String> cs;
              CompletableFuture<String> cf =
                  CompletableFuture.completedFuture(
                      partition.toString() + "-" + event.sequenceNumber());
              if (random.nextBoolean()) {
                cs = Patterns.after(Duration.ofMillis(random.nextInt(1000)), system, () -> cf);
              } else {
                cs = cf;
              }

              return cs.thenApply(
                  s -> {
                    System.out.println("Completed processing " + s);
                    return s;
                  });
            })
        .map(in -> "`mapAsyncPartitioned` emitted " + in)
        .runWith(Sink.foreach(str -> System.out.println(str)), system);
    // #mapAsyncPartitioned
  }

  public static void main(String[] args) {
    MapAsyncs mapAsyncs = new MapAsyncs();
    if (args[0].equals("partitioned")) {
      mapAsyncs.runPartitioned();
    } else if (args[0].equals("strict")) {
      mapAsyncs.runStrictOrder();
    } else if (args[0].equals("unordered")) {
      mapAsyncs.runUnordered();
    } else {
      mapAsyncs.run();
    }
  }

  static interface Event {
    public int sequenceNumber();
  }

  static class PlainEvent implements Event {
    public final int _sequenceNumber;

    public PlainEvent(int sequenceNumber) {
      this._sequenceNumber = sequenceNumber;
    }

    public int sequenceNumber() {
      return _sequenceNumber;
    }

    @Override
    public String toString() {
      return "Event(" + _sequenceNumber + ')';
    }
  }

  static class EntityEvent implements Event {
    public final int entityId;
    public final int _sequenceNumber;

    public EntityEvent(int entityId, int sequenceNumber) {
      this.entityId = entityId;
      this._sequenceNumber = sequenceNumber;
    }

    public int sequenceNumber() {
      return _sequenceNumber;
    }

    @Override
    public String toString() {
      return "EntityEvent(" + entityId + ", " + _sequenceNumber + ")";
    }
  }
}
