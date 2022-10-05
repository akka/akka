/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.sourceorflow;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.japi.function.Function;
import akka.pattern.Patterns;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public class MapAsyncs {

  private static final Random random = new Random();

  // dummy objects for our pretend Kafka...
  private Object settings = new Object();
  private Object subscription = new Object();

  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  private final Source<Event, NotUsed> events =
      Consumer.plainSource(settings, subscription).throttle(1, Duration.ofMillis(50));
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
    // pretending this is an Alpakka Kafka-style CommitterSettings
    String commitSettings = "`mapAsyncPartitioned`";

    // #mapAsyncPartitioned
    Function<EntityEvent, Integer> partitioner =
        (event) -> {
          Integer partition = Integer.valueOf(event.entityId);
          System.out.println("Assigned event " + event + " to partition " + event.entityId);
          return partition;
        };

    Consumer.committableSource(settings, subscription)
        .take(1000)
        .statefulMap(
            () -> Integer.valueOf(0),
            (count, event) -> {
              System.out.println(
                  "Received event " + event + " at offset " + count + " from message broker");
              return Pair.create(count + 1, event);
            },
            (count) -> Optional.empty())
        .mapAsyncPartitioned(
            10, // parallelism
            1, // perPartition
            partitioner,
            (event, partition) -> {
              System.out.println("Processing event " + event + " from partition " + partition);

              // processing result is "partition-sequenceNr"
              // public CompletionStage<String> processEvent(EntityEvent event, Integer partition)
              CompletionStage<String> cs = processEvent(event, partition);

              return cs.thenApply(
                  s -> {
                    System.out.println("Completed processing " + s);
                    return s;
                  });
            })
        // for this purpose of this example, will print every element, prepended with
        // "`mapAsyncPartitioned` emitted "
        .runWith(Committer.sink(commitSettings), system);
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

  // Pretend to be asking an entity from an event... response is sometimes delayed by up to a second
  public CompletionStage<String> processEvent(EntityEvent event, Integer partition) {
    CompletableFuture<String> cf =
        CompletableFuture.completedFuture(partition.toString() + "-" + event.sequenceNumber());

    if (random.nextBoolean()) {
      return Patterns.after(Duration.ofMillis(random.nextInt(1000)), system, () -> cf);
    } else {
      return cf;
    }
  }

  private static class Consumer {
    // almost but not quite, like Alpakka Kafka...
    public static Source<EntityEvent, NotUsed> committableSource(
        Object settings, Object subscription) {
      // pro forma use the dummy arguments
      if (settings != null && subscription != null) {
        return Source.fromIterator(() -> Stream.iterate(1, i -> i + 1).iterator())
            .statefulMap(
                () -> new HashMap<Integer, Integer>(), // create: countsPerEntity
                (countsPerEntity, n) -> {
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

                  int seqNr =
                      countsPerEntity
                              .getOrDefault(Integer.valueOf(entityId), Integer.valueOf(0))
                              .intValue()
                          + 1;

                  countsPerEntity.put(Integer.valueOf(entityId), Integer.valueOf(seqNr));

                  return new Pair<HashMap<Integer, Integer>, EntityEvent>(
                      countsPerEntity, new EntityEvent(entityId, seqNr));
                }, // f
                (countsPerEntity) -> {
                  return Optional.empty();
                } // onComplete
                )
            .take(1000);
      } else {
        throw new AssertionError("pro forma");
      }
    }

    // likewise ape the Alpakka Kafka API
    public static Source<Event, NotUsed> plainSource(Object settings, Object subscription) {
      // pro forma use the dummy arguments
      if (settings != null && subscription != null) {
        return Source.fromIterator(() -> Stream.iterate(1, i -> i + 1).iterator())
            .map(PlainEvent::new);
      } else {
        throw new AssertionError("pro forma");
      }
    }
  }

  private static class Committer {
    public static Sink<String, CompletionStage<Done>> sink(String prependTo) {
      return Flow.of(String.class)
          .map(in -> prependTo + " emitted " + in)
          .toMat(Sink.foreach(str -> System.out.println(str)), Keep.right());
    }
  }
}
