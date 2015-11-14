/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream.io;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;

import akka.actor.ActorSystem;
import akka.stream.ActorAttributes;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import docs.stream.SilenceSystemOut;
import docs.stream.cookbook.RecipeParseLines;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Future;
import scala.runtime.BoxedUnit;

import akka.stream.*;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;

public class StreamFileDocTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamFileDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  final SilenceSystemOut.System System = SilenceSystemOut.get();

  {
    //#file-source
    final File file = new File("example.csv");
    //#file-source
  }

  @Test
  public void demonstrateMaterializingBytesWritten() throws IOException {
    final File file = File.createTempFile(getClass().getName(), ".tmp");

    try {
      //#file-source
      Sink<ByteString, Future<BoxedUnit>> printlnSink =
        Sink.foreach(chunk -> System.out.println(chunk.utf8String()));

      Future<Long> bytesWritten =
        Source.file(file)
          .to(printlnSink)
          .run(mat);
      //#file-source
    } finally {
      file.delete();
    }
  }

  @Test
  public void demonstrateSettingDispatchersInCode() throws IOException {
    final File file = File.createTempFile(getClass().getName(), ".tmp");

    try {
      Sink<ByteString, Future<Long>> byteStringFutureSink =
      //#custom-dispatcher-code
      Sink.file(file)
        .withAttributes(ActorAttributes.dispatcher("custom-blocking-io-dispatcher"));
      //#custom-dispatcher-code
    } finally {
      file.delete();
    }
  }


}
