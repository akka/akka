/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RecipeToStrict extends RecipeTest {
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
  public void workWithPrintln() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<String, BoxedUnit> myData = Source.from(Arrays.asList("1", "2", "3"));
        final int MAX_ALLOWED_SIZE = 100;

        //#draining-to-list
        final Future<List<String>> strings = myData
          .grouped(MAX_ALLOWED_SIZE).runWith(Sink.head(), mat);
        //#draining-to-list

        Await.result(strings, new FiniteDuration(1, TimeUnit.SECONDS));
      }
    };
  }

}
