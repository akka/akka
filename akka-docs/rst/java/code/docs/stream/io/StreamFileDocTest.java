/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream.io;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletionStage;

import akka.Done;
import akka.actor.ActorSystem;
import akka.stream.ActorAttributes;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.FileIO;
import docs.AbstractJavaTest;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Future;

import akka.stream.*;
import akka.testkit.JavaTestKit;
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
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }


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
      Sink<ByteString, CompletionStage<Done>> printlnSink =
        Sink.<ByteString> foreach(chunk -> System.out.println(chunk.utf8String()));

      CompletionStage<IOResult> ioResult =
        FileIO.fromFile(file)
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
      Sink<ByteString, CompletionStage<IOResult>> fileSink =
      //#custom-dispatcher-code
      FileIO.toFile(file)
        .withAttributes(ActorAttributes.dispatcher("custom-blocking-io-dispatcher"));
      //#custom-dispatcher-code
    } finally {
      file.delete();
    }
  }


}
