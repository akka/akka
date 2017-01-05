/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actorlambda;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.FI;
import akka.japi.pf.ReceiveBuilder;
import akka.testkit.JavaTestKit;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

public class InitializationDocTest extends AbstractJavaTest {

  static ActorSystem system = null;

  @BeforeClass
  public static void beforeClass() {
    system = ActorSystem.create("InitializationDocTest");
  }

  @AfterClass
  public static void afterClass() throws Exception {
    Await.ready(system.terminate(), Duration.create("5 seconds"));
  }

  public static class MessageInitExample extends AbstractActor {
    private String initializeMe = null;

    public MessageInitExample() {
      //#messageInit
      receive(ReceiveBuilder.
          matchEquals("init", m1 -> {
            initializeMe = "Up and running";
            context().become(ReceiveBuilder.
              matchEquals("U OK?", m2 -> {
                sender().tell(initializeMe, self());
              }).build());

          }).build()
        //#messageInit
      );
    }
  }

  public class GenericMessage<T> {
    T value;

    public GenericMessage(T value) {
      this.value = value;
    }
  }

  public static class GenericActor extends AbstractActor {
    public GenericActor() {
      receive(ReceiveBuilder.match(GenericMessage.class, (GenericMessage<String> msg) -> {
        GenericMessage<String> message = msg;
        sender().tell(message.value.toUpperCase(), self());

      }).build());


    }
  }

  static class GenericActorWithPredicate extends AbstractActor {
    public GenericActorWithPredicate() {
      FI.TypedPredicate<GenericMessage<String>> typedPredicate = s -> !s.value.isEmpty();

      receive(ReceiveBuilder.match(GenericMessage.class, typedPredicate, (GenericMessage<String> msg) -> {
        sender().tell(msg.value.toUpperCase(), self());
      }).build());
    }
  }

  @Test
  public void testIt() {

    new JavaTestKit(system) {{
      ActorRef testactor = system.actorOf(Props.create(MessageInitExample.class), "testactor");
      String msg = "U OK?";

      testactor.tell(msg, getRef());
      expectNoMsg(Duration.create(1, TimeUnit.SECONDS));

      testactor.tell("init", getRef());
      testactor.tell(msg, getRef());
      expectMsgEquals("Up and running");
    }};
  }

  @Test
  public void testGenericActor() {
    new JavaTestKit(system) {{
      ActorRef genericTestActor = system.actorOf(Props.create(GenericActor.class), "genericActor");
      GenericMessage<String> genericMessage = new GenericMessage<String>("a");

      genericTestActor.tell(genericMessage, getRef());
      expectMsgEquals("A");
    }};
  }

  @Test
  public void actorShouldNotRespondForEmptyMessage() {
    new JavaTestKit(system) {{
      ActorRef genericTestActor = system.actorOf(Props.create(GenericActorWithPredicate.class), "genericActorWithPredicate");
      GenericMessage<String> emptyGenericMessage = new GenericMessage<String>("");
      GenericMessage<String> nonEmptyGenericMessage = new GenericMessage<String>("a");

      genericTestActor.tell(emptyGenericMessage, getRef());
      expectNoMsg();

      genericTestActor.tell(nonEmptyGenericMessage, getRef());
      expectMsgEquals("A");
    }};
  }
}
