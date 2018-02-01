package jdocs.akka.typed.testing.sync;

//#imports
import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.testkit.typed.*;
//#imports
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class BasicSyncTestingTest extends JUnitSuite {

  //#child
  public static Behavior<String> childActor = Behaviors.immutable((ctx, msg) -> Behaviors.same());
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

  public static Behavior<Command> myBehavior = Behaviors.immutable(Command.class)
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
    BehaviorTestkit<Command> test = BehaviorTestkit.create(myBehavior);
    test.run(new CreateAChild("child"));
    test.expectEffect(new Effect.Spawned(childActor, "child", Props.empty()));
    //#test-child
  }

  @Test
  public void testSpawningAnonymous() {
    //#test-anonymous-child
    BehaviorTestkit<Command> test = BehaviorTestkit.create(myBehavior);
    test.run(new CreateAnAnonymousChild());
    test.expectEffect(new Effect.SpawnedAnonymous(childActor, Props.empty()));
    //#test-anonymous-child
  }

  @Test
  public void testRecodingMessageSend() {
    //#test-message
    BehaviorTestkit<Command> test = BehaviorTestkit.create(myBehavior);
    TestInbox<String> inbox = new TestInbox<String>();
    test.run(new SayHello(inbox.ref()));
    inbox.expectMsg("hello");
    //#test-message
  }

  @Test
  public void testMessageToChild() {
     //#test-child-message
     BehaviorTestkit<Command> testKit = BehaviorTestkit.create(myBehavior);
     testKit.run(new SayHelloToChild("child"));
     TestInbox<String> childInbox = testKit.childInbox("child");
     childInbox.expectMsg("hello");
     //#test-child-message
  }

  @Test
  public void testMessageToAnonymousChild() {
     //#test-child-message-anonymous
     BehaviorTestkit<Command> testKit = BehaviorTestkit.create(myBehavior);
     testKit.run(new SayHelloToAnonymousChild());
     // Anonymous actors are created as: $a $b etc
     TestInbox<String> childInbox = testKit.childInbox("$a");
     childInbox.expectMsg("hello stranger");
     //#test-child-message-anonymous
  }
}
