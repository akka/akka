/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.sourceorflow;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.japi.Pair;
import akka.japi.function.Function;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import docs.stream.operators.sourceorflow.ExtrapolateScala;
import docs.stream.operators.sourceorflow.ExtrapolateScala.Frame;

import java.time.Duration;
import java.util.stream.Stream;

/** */
public class ExtrapolateJava {
  public static Function<ByteString, Frame> decodeAsFrame = ExtrapolateScala.Frame$.MODULE$::decode;

  public static Frame BLACK_FRAME = ExtrapolateScala.Frame$.MODULE$.blackFrame();

  public static long nowInSeconds() {
    return ExtrapolateScala.nowInSeconds();
  }

  public static void main(String[] args) {
    ActorSystem actorSystem = ActorSystem.create("25fps-stream");

    Source<ByteString, NotUsed> networkSource = ExtrapolateScala.networkSource().asJava();

    Flow<ByteString, Frame, NotUsed> decode = Flow.of(ByteString.class).<Frame>map(decodeAsFrame);

    // #extrapolate
    // if upstream is too slow, produce copies of the last frame but grayed out.
    Flow<Frame, Frame, NotUsed> rateControl =
        Flow.of(Frame.class)
            .extrapolate(
                lastFrame -> {
                  Frame gray =
                      new Frame(
                          ByteString.fromString(
                              "gray frame!! - " + lastFrame.pixels().utf8String()));
                  return Stream.iterate(gray, i -> i).iterator();
                },
                BLACK_FRAME // initial value
                );

    Source<Frame, NotUsed> videoSource = networkSource.via(decode).via(rateControl);

    // let's create a 25fps stream (a Frame every 40.millis)
    Source<String, Cancellable> tickSource =
        Source.tick(Duration.ZERO, Duration.ofMillis(40), "tick");

    Source<Frame, Cancellable> videoAt25Fps = tickSource.zip(videoSource).map(Pair::second);

    // #extrapolate
    videoAt25Fps
        .map(Frame::pixels)
        .map(ByteString::utf8String)
        .map(pixels -> nowInSeconds() + " - " + pixels)
        .to(Sink.foreach(System.out::println))
        .run(actorSystem);
  }
}
