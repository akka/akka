package jdocs.stream.operators.sourceorflow;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.japi.pf.Match;
import akka.stream.FlowMonitor;
import akka.stream.FlowMonitorState;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import scala.PartialFunction;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 */
public class Monitor {

    private static final PartialFunction<Object, NotUsed> printMonitorState = Match.match(
            FlowMonitorState.Initialized$.class,
            __ -> {
                System.out.println("Stream is initialized but hasn't processed any element");
                return NotUsed.getInstance();
            }
    ).match(
            FlowMonitorState.Received.class,
            (msg) -> {
                System.out.println("Last message received: " + msg.msg());
                return NotUsed.getInstance();
            }
    ).match(
            FlowMonitorState.Failed.class,
            (failed) -> {
                System.out.println("Stream failed with cause: " + failed.cause().getMessage());
                return NotUsed.getInstance();
            }
    ).match(
            FlowMonitorState.Finished$.class,
            __ -> {
                System.out.println("Stream completed already");
                return NotUsed.getInstance();
            }
    ).build();


    public static void main(String[] args) throws InterruptedException, TimeoutException, ExecutionException {
        ActorSystem actorSystem = ActorSystem.create("25fps-stream");

        Source<Integer, FlowMonitor<Integer>> monitoredSource =
                Source
                        .fromIterator(() -> Arrays.asList(0, 1, 2, 3, 4, 5).iterator())
                        .throttle(5, Duration.ofSeconds(1))
                        .monitorMat(Keep.right());


        Pair<FlowMonitor<Integer>, CompletionStage<Done>> run = monitoredSource
                .toMat(Sink.foreach(System.out::println), Keep.both()).run(actorSystem);

        FlowMonitor<Integer> monitor = run.first();

        // if we peek on the stream too early it probably won't have processed any element.
        printMonitorState.apply(monitor.state());

        // wait a few millis and peek in the stream again to see what's the latest element processed
        Thread.sleep(500);
        printMonitorState.apply(monitor.state());

        // wait until the stream completed
        run.second().toCompletableFuture().get(1, TimeUnit.SECONDS);
        printMonitorState.apply(monitor.state());


        run.second().toCompletableFuture().whenComplete((x, t) -> actorSystem.terminate());

    }
}
