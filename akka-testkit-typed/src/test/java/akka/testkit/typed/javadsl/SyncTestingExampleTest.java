/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.javadsl;

//#imports
import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
//#imports
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class SyncTestingExampleTest extends JUnitSuite {

  //#child
  public static Behavior<String> childActor = Behaviors.receive((ctx, msg) -> Behaviors.same());
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

  public static Behavior<Command> myBehavior = Behaviors.receive(Command.class)
    .onMessage(CreateAChild.class, (ctx, msg) -> {
      ctx.spawn(childActor, msg.childName);
      return Behaviors.same();
    })
    .onMessage(CreateAnAnonymousChild.class, (ctx, msg) -> {
      ctx.spawnAnonymous(childActor);
      return Behaviors.same();
    })
    .onMessage(SayHelloToChild.class, (ctx, msg) -> {
      ActorRef<String> child = ctx.spawn(childActor, msg.childName);
      child.tell("hello");
      return Behaviors.same();
    })
    .onMessage(SayHelloToAnonymousChild.class, (ctx, msg) -> {
      ActorRef<String> child = ctx.spawnAnonymous(childActor);
      child.tell("hello stranger");
      return Behaviors.same();
    }).onMessage(SayHello.class, (ctx, msg) -> {
      msg.who.tell("hello");
      return Behaviors.same();
    }).build();
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
}
