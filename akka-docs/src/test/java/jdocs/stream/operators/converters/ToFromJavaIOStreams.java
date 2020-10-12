/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.converters;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.IOResult;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.StreamConverters;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;
import jdocs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;

public class ToFromJavaIOStreams extends AbstractJavaTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("ToFromJavaIOStreams");
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void demonstrateFromJavaIOStreams()
      throws InterruptedException, ExecutionException, TimeoutException {
    Charset charset = Charset.defaultCharset();
    byte[] bytes = "Some random input".getBytes(charset);
    Flow<ByteString, ByteString, NotUsed> toUpperCase =
        Flow.<ByteString>create()
            .map(
                bs -> {
                  String str = bs.decodeString(charset).toUpperCase();
                  return ByteString.fromString(str, charset);
                });

    // #tofromJavaIOStream
    java.io.InputStream inputStream = new ByteArrayInputStream(bytes);
    Source<ByteString, CompletionStage<IOResult>> source =
        StreamConverters.fromInputStream(() -> inputStream);

    // Given a ByteString produces a ByteString with the upperCase representation
    // Removed from the sample for brevity.
    // Flow<ByteString, ByteString, NotUsed> toUpperCase = ...

    java.io.OutputStream outputStream = new ByteArrayOutputStream();
    Sink<ByteString, CompletionStage<IOResult>> sink =
        StreamConverters.fromOutputStream(() -> outputStream);

    CompletionStage<IOResult> ioResultCompletionStage =
        source.via(toUpperCase).runWith(sink, system);
    // When the ioResultCompletionStage completes, the byte array backing the outputStream
    // will contain the uppercase representation of the bytes read from the inputStream.
    // #tofromJavaIOStream
    ioResultCompletionStage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(
        "SOME RANDOM INPUT",
        new String(((ByteArrayOutputStream) outputStream).toByteArray(), charset));
  }
}
