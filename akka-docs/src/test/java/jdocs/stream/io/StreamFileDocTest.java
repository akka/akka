/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.concurrent.CompletionStage;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorAttributes;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Source;
import jdocs.AbstractJavaTest;
import jdocs.stream.SilenceSystemOut;
import akka.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.stream.*;
import akka.util.ByteString;

public class StreamFileDocTest extends AbstractJavaTest {

  static ActorSystem system;

  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamFileDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  final SilenceSystemOut.System System = SilenceSystemOut.get();

  {
    // Using 4 spaces here to align with code in try block below.
    // #file-source
    final Path file = Paths.get("example.csv");
    // #file-source
  }

  {
    // #file-sink
    final Path file = Paths.get("greeting.txt");
    // #file-sink
  }

  @Test
  public void demonstrateMaterializingBytesWritten() throws IOException {
    final Path file = Files.createTempFile(getClass().getName(), ".tmp");

    try {
      // #file-source
      Sink<ByteString, CompletionStage<Done>> printlnSink =
          Sink.<ByteString>foreach(chunk -> System.out.println(chunk.utf8String()));

      CompletionStage<IOResult> ioResult = FileIO.fromPath(file).to(printlnSink).run(mat);
      // #file-source
    } finally {
      Files.delete(file);
    }
  }

  @Test
  public void demonstrateSettingDispatchersInCode() throws IOException {
    final Path file = Files.createTempFile(getClass().getName(), ".tmp");

    try {
      Sink<ByteString, CompletionStage<IOResult>> fileSink =
          // #custom-dispatcher-code
          FileIO.toPath(file)
              .withAttributes(ActorAttributes.dispatcher("custom-blocking-io-dispatcher"));
      // #custom-dispatcher-code
    } finally {
      Files.delete(file);
    }
  }

  @Test
  public void demontrateFileIOWriting() throws IOException {
    final Path file = Files.createTempFile(getClass().getName(), ".tmp");

    try {
      // #file-sink
      Sink<ByteString, CompletionStage<IOResult>> fileSink = FileIO.toPath(file);
      Source<String, NotUsed> textSource = Source.single("Hello Akka Stream!");

      CompletionStage<IOResult> ioResult =
          textSource.map(ByteString::fromString).runWith(fileSink, mat);
      // #file-sink
    } finally {
      Files.delete(file);
    }
  }
}
