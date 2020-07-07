/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.sourceorflow;

import akka.Done;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.FlowMonitor;
import akka.stream.FlowMonitorState;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** */
public class Monitor {

  // #monitor
  private static <T> void printMonitorState(FlowMonitorState.StreamState<T> state) {
    if (FlowMonitorState.Finished$.class.isInstance(state)) {
      System.out.println("Stream is initialized but hasn't processed any element");
    } else if (FlowMonitorState.Received.class.isInstance(state)) {
      FlowMonitorState.Received msg = (FlowMonitorState.Received) state;
      System.out.println("Last message received: " + msg.msg());
    } else if (FlowMonitorState.Failed.class.isInstance(state)) {
      Throwable cause = ((FlowMonitorState.Failed) state).cause();
      System.out.println("Stream failed with cause: " + cause.getMessage());
    } else {
      System.out.println("Stream completed already");
    }
  }
  // #monitor

  public static void main(String[] args)
      throws InterruptedException, TimeoutException, ExecutionException {
    ActorSystem actorSystem = ActorSystem.create("25fps-stream");

    // #monitor
    Source<Integer, FlowMonitor<Integer>> monitoredSource =
        Source.fromIterator(() -> Arrays.asList(0, 1, 2, 3, 4, 5).iterator())
            .throttle(5, Duration.ofSeconds(1))
            .monitorMat(Keep.right());

    Pair<FlowMonitor<Integer>, CompletionStage<Done>> run =
        monitoredSource.toMat(Sink.foreach(System.out::println), Keep.both()).run(actorSystem);

    FlowMonitor<Integer> monitor = run.first();

    // if we peek on the stream too early it probably won't have processed any element.
    printMonitorState(monitor.state());

    // At this point, the application will continue to run and future
    // invocations to `printMonitorState(flowMonitor)` will continue to show
    // the progress in the stream
    // #monitor

    // Don't use `Thread#sleep` in your code. It's a blocking call
    // that can starve the thread-pool.
    Thread.sleep(500);

    // sometime later, our code has progressed. We can peek in the stream
    // again to see what's the latest element processed
    printMonitorState(monitor.state());

    // Don't use `CompletableFuture#get` in your code. It's a blocking call
    // that can starve the thread-pool.
    run.second().toCompletableFuture().get(1, TimeUnit.SECONDS);
    // Eventually, the stream completes and if we check the state it reports the streasm finished.
    printMonitorState(monitor.state());

    run.second().toCompletableFuture().whenComplete((x, t) -> actorSystem.terminate());
  }
}
