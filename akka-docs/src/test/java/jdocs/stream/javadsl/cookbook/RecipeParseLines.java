/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Framing;
import akka.stream.javadsl.FramingTruncation;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class RecipeParseLines extends RecipeTest {

  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeParseLines");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  @Test
  public void parseLines() throws Exception {
    final Source<ByteString, NotUsed> rawData = Source.from(Arrays.asList(
      ByteString.fromString("Hello World"),
      ByteString.fromString("\r"),
      ByteString.fromString("!\r"),
      ByteString.fromString("\nHello Akka!\r\nHello Streams!"),
      ByteString.fromString("\r\n\r\n")));

    //#parse-lines
    final Source<String, NotUsed> lines = rawData
      .via(Framing.delimiter(ByteString.fromString("\r\n"), 100, FramingTruncation.ALLOW))
      .map(b -> b.utf8String());
    //#parse-lines
    lines.limit(10).runWith(Sink.seq(), mat).toCompletableFuture().get(1, TimeUnit.SECONDS);
  }

}
