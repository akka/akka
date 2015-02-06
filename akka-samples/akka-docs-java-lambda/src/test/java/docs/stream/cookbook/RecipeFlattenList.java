/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class RecipeFlattenList extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeFlattenList");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void workWithPrintln() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        Source<List<Message>> someDataSource = Source.from(Arrays.asList(
          Arrays.asList(new Message("1")),
          Arrays.asList(new Message("2"), new Message("3"))));

        //#flattening-lists
        Source<List<Message>> myData = someDataSource;
        Source<Message> flattened = myData.mapConcat(i -> i);
        //#flattening-lists

        List<Message> got = Await.result(flattened.grouped(10).runWith(Sink.head(), mat), new FiniteDuration(1, TimeUnit.SECONDS));
        assertEquals(got.get(0), new Message("1"));
        assertEquals(got.get(1), new Message("2"));
        assertEquals(got.get(2), new Message("3"));
      }
    };
  }

}
