/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.testkit.StreamTestKit;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RecipeSimpleDrop extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeSimpleDrop");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {

      //#simple-drop
        final Flow<Message, Message> droppyStream =
          Flow.of(Message.class).conflate(i -> i, (lastMessage, newMessage) -> newMessage);
        //#simple-drop

        // TODO TESTS

        new StreamTestKit.PublisherProbe(system);
      }
    };
  }
}

