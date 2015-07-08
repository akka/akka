/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.io.Framing;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import scala.concurrent.Await;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

public class RecipeParseLines extends RecipeTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLoggingElements");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  @Test
  public void parseLines() throws Exception {
    final Source<ByteString, BoxedUnit> rawData = Source.from(Arrays.asList(
      ByteString.fromString("Hello World"),
      ByteString.fromString("\r"),
      ByteString.fromString("!\r"),
      ByteString.fromString("\nHello Akka!\r\nHello Streams!"),
      ByteString.fromString("\r\n\r\n")));

    //#parse-lines
    final Source<String, BoxedUnit> lines = rawData
      .via(Framing.delimiter(ByteString.fromString("\r\n"), 100, true))
      .map(b -> b.utf8String());
    //#parse-lines

    Await.result(lines.grouped(10).runWith(Sink.head(), mat), new FiniteDuration(1, TimeUnit.SECONDS));
  }

}
