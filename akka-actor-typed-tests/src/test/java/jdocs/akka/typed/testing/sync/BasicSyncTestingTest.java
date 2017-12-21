package jdocs.akka.typed.testing.sync;

//#imports
import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.typed.testkit.*;
//#imports
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class BasicSyncTestingTest extends JUnitSuite {

  //#child
  public static Behavior<String> childActor = Actor.immutable((ctx, msg) -> Actor.same());
  //#child

  //#under-test
  interface Command { }
  public static class CreateAChild implements Command { }
  public static class CreateAnAnonymousChild implements Command { }
  public static class SayHelloToChild implements Command { }
  public static class SayHelloToAnonymousChild implements Command { }
  public static class SayHello implements Command {
    private final ActorRef<String> who;

    public SayHello(ActorRef<String> who) {
      this.who = who;
    }
  }

  public static Behavior<Command> myBehaviour = Actor.immutable(Command.class)
    .onMessage(CreateAChild.class, (ctx, msg) -> {
      ctx.spawn(childActor, "child");
      return Actor.same();
    })
    .onMessage(CreateAnAnonymousChild.class, (ctx, msg) -> {
      ctx.spawnAnonymous(childActor);
      return Actor.same();
    })
    .onMessage(SayHelloToChild.class, (ctx, msg) -> {
      ActorRef<String> child = ctx.spawn(childActor, "child");
      child.tell("hello");
      return Actor.same();
    })
    .onMessage(SayHelloToAnonymousChild.class, (ctx, msg) -> {
      ActorRef<String> child = ctx.spawnAnonymous(childActor);
      child.tell("hello stranger");
      return Actor.same();
    }).onMessage(SayHello.class, (ctx, msg) -> {
      msg.who.tell("hello");
      return Actor.same();
    }).build();
  //#under-test


  @Test
  public void testSpawning() {
    //#test-child
    BehaviorTestkit<Command> test = BehaviorTestkit.create(myBehaviour);
    test.run(new CreateAChild());
    test.expectEffect(new Effect.Spawned(childActor, "child", Props.empty()));
    //#test-child
  }

  @Test
  public void testSpawningAnonymous() {
    //#test-anonymous-child
    BehaviorTestkit<Command> test = BehaviorTestkit.create(myBehaviour);
    test.run(new CreateAnAnonymousChild());
    test.expectEffect(new Effect.SpawnedAnonymous(childActor, Props.empty()));
    //#test-anonymous-child
  }

  @Test
  public void testRecodingMessageSend() {
    //#test-message
    BehaviorTestkit<Command> test = BehaviorTestkit.create(myBehaviour);
    TestInbox<String> inbox = new TestInbox<String>();
    test.run(new SayHello(inbox.ref()));
    inbox.expectMsg("hello");
    //#test-message
  }

  @Test
  public void testMessageToChild() {
     //#test-child-message
     BehaviorTestkit<Command> testKit = BehaviorTestkit.create(myBehaviour);
     testKit.run(new SayHelloToChild());
     TestInbox<String> childInbox = testKit.childInbox("child");
     childInbox.expectMsg("hello");
     //#test-child-message
  }

  @Test
  public void testMessageToAnonymousChild() {
     //#test-child-message-anonymous
     BehaviorTestkit<Command> testKit = BehaviorTestkit.create(myBehaviour);
     testKit.run(new SayHelloToAnonymousChild());
     // Anonymous actors are created as: $a $b etc
     TestInbox<String> childInbox = testKit.childInbox("$a");
     childInbox.expectMsg("hello stranger");
     //#test-child-message-anonymous
  }
}
