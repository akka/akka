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
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

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

  final Materializer mat = ActorMaterializer.create(system);

  @Test
  public void workWithMapConcat() throws Exception {
    new JavaTestKit(system) {
      {
        Source<List<Message>, BoxedUnit> someDataSource = Source
          .from(Arrays.asList(Arrays.asList(new Message("1")), Arrays.asList(new Message("2"), new Message("3"))));

        //#flattening-lists
        Source<List<Message>, BoxedUnit> myData = someDataSource;
        Source<Message, BoxedUnit> flattened = myData.mapConcat(i -> i);
        //#flattening-lists

        List<Message> got = Await.result(flattened.grouped(10).runWith(Sink.head(), mat),
          new FiniteDuration(1, TimeUnit.SECONDS));
        assertEquals(got.get(0), new Message("1"));
        assertEquals(got.get(1), new Message("2"));
        assertEquals(got.get(2), new Message("3"));
      }
    };
  }

}
