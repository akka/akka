/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.actor.testkit.typed.javadsl;

//#imports
import akka.actor.testkit.typed.CapturedLogEvent;
import akka.actor.testkit.typed.javadsl.BehaviorTestKit;
import akka.actor.testkit.typed.javadsl.Effects;
import akka.actor.testkit.typed.javadsl.TestInbox;
import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.event.Logging;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
//#imports
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import org.scalatest.junit.JUnitSuite;


public class SyncTestingExampleTest extends JUnitSuite {

  //#child
  public static Behavior<String> childActor = Behaviors.receive((context, message) -> Behaviors.same());
  //#child

  //#under-test
  interface Command { }
  public static class CreateAChild implements Command {
    private final String childName;
    public CreateAChild(String childName) {
      this.childName = childName;
    }
  }
  public static class CreateAnAnonymousChild implements Command { }
  public static class SayHelloToChild implements Command {
    private final String childName;
    public SayHelloToChild(String childName) {
      this.childName = childName;
    }
  }
  public static class SayHelloToAnonymousChild implements Command { }
  public static class SayHello implements Command {
    private final ActorRef<String> who;

    public SayHello(ActorRef<String> who) {
      this.who = who;
    }
  }
  public static class LogAndSayHello implements Command {
    private final ActorRef<String> who;

    public LogAndSayHello(ActorRef<String> who) {
      this.who = who;
    }
  }

  public static Behavior<Command> myBehavior = Behaviors.receive(Command.class)
    .onMessage(CreateAChild.class, (context, message) -> {
      context.spawn(childActor, message.childName);
      return Behaviors.same();
    })
    .onMessage(CreateAnAnonymousChild.class, (context, message) -> {
      context.spawnAnonymous(childActor);
      return Behaviors.same();
    })
    .onMessage(SayHelloToChild.class, (context, message) -> {
      ActorRef<String> child = context.spawn(childActor, message.childName);
      child.tell("hello");
      return Behaviors.same();
    })
    .onMessage(SayHelloToAnonymousChild.class, (context, message) -> {
      ActorRef<String> child = context.spawnAnonymous(childActor);
      child.tell("hello stranger");
      return Behaviors.same();
    })
    .onMessage(SayHello.class, (context, message) -> {
      message.who.tell("hello");
      return Behaviors.same();
    })
    .onMessage(LogAndSayHello.class, (context, message) -> {
      context.getLog().info("Saying hello to {}", message.who.path().name());
      message.who.tell("hello");
      return Behaviors.same();
    })
    .build();
  //#under-test


  @Test
  public void testSpawning() {
    //#test-child
    BehaviorTestKit<Command> test = BehaviorTestKit.create(myBehavior);
    test.run(new CreateAChild("child"));
    test.expectEffect(Effects.spawned(childActor, "child", Props.empty()));
    //#test-child
  }

  @Test
  public void testSpawningAnonymous() {
    //#test-anonymous-child
    BehaviorTestKit<Command> test = BehaviorTestKit.create(myBehavior);
    test.run(new CreateAnAnonymousChild());
    test.expectEffect(Effects.spawnedAnonymous(childActor, Props.empty()));
    //#test-anonymous-child
  }

  @Test
  public void testRecodingMessageSend() {
    //#test-message
    BehaviorTestKit<Command> test = BehaviorTestKit.create(myBehavior);
    TestInbox<String> inbox = TestInbox.create();
    test.run(new SayHello(inbox.getRef()));
    inbox.expectMessage("hello");
    //#test-message
  }

  @Test
  public void testMessageToChild() {
     //#test-child-message
     BehaviorTestKit<Command> testKit = BehaviorTestKit.create(myBehavior);
     testKit.run(new SayHelloToChild("child"));
     TestInbox<String> childInbox = testKit.childInbox("child");
     childInbox.expectMessage("hello");
     //#test-child-message
  }

  @Test
  public void testMessageToAnonymousChild() {
     //#test-child-message-anonymous
     BehaviorTestKit<Command> testKit = BehaviorTestKit.create(myBehavior);
     testKit.run(new SayHelloToAnonymousChild());
     // Anonymous actors are created as: $a $b etc
     TestInbox<String> childInbox = testKit.childInbox("$a");
     childInbox.expectMessage("hello stranger");
     //#test-child-message-anonymous
  }

  @Test
  public void testCheckLogging() {
    //#test-check-logging
    BehaviorTestKit<Command> test = BehaviorTestKit.create(myBehavior);
    TestInbox<String> inbox = TestInbox.create("Inboxer");
    test.run(new LogAndSayHello(inbox.getRef()));

    List<CapturedLogEvent> allLogEntries = test.getAllLogEntries();
    assertEquals(1, allLogEntries.size());
    CapturedLogEvent expectedLogEvent = new CapturedLogEvent(Logging.InfoLevel(),"Saying hello to Inboxer",
            Optional.empty(), Optional.empty(), new HashMap<>());
    assertEquals(expectedLogEvent, allLogEntries.get(0));
    //#test-check-logging
  }
}
