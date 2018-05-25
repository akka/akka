/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl;

import akka.Done;
import akka.actor.testkit.typed.Effect;
import akka.actor.testkit.typed.Effects;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.Ignore;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BehaviorTestKitTest extends JUnitSuite {

  public interface Command {
  }

  public static class SpawnWatchAndUnWatch implements Command {
    private final String name;

    public SpawnWatchAndUnWatch(String name) {
      this.name = name;
    }
  }

  public static class SpawnAndWatchWith implements Command {
    private final String name;

    public SpawnAndWatchWith(String name) {
      this.name = name;
    }
  }

  public static class SpawnSession implements Command {
    private final ActorRef<ActorRef<String>> replyTo;
    private final ActorRef<String> sessionHandler;

    public SpawnSession(ActorRef<ActorRef<String>> replyTo, ActorRef<String> sessionHandler) {
      this.replyTo = replyTo;
      this.sessionHandler = sessionHandler;
    }
  }

  public static class KillSession implements Command {
    private final ActorRef<String> session;
    private final ActorRef<Done> replyTo;

    public KillSession(ActorRef<String> session, ActorRef<Done> replyTo) {
      this.session = session;
      this.replyTo = replyTo;
    }
  }

  public static class CreateMessageAdapter implements Command {
    private final Class<Object> clazz;
    private final Function<Object, Command> f;

    public CreateMessageAdapter(Class clazz, Function<Object, Command> f) {
      this.clazz = clazz;
      this.f = f;
    }
  }

  public static class SpawnChildren implements Command {
    private final int numberOfChildren;

    public SpawnChildren(int numberOfChildren) {
      this.numberOfChildren = numberOfChildren;
    }
  }

  public static class SpawnChildrenAnonymous implements Command {
    private final int numberOfChildren;

    public SpawnChildrenAnonymous(int numberOfChildren) {
      this.numberOfChildren = numberOfChildren;
    }
  }

  public static class SpawnChildrenWithProps implements Command {
    private final int numberOfChildren;
    private final Props props;

    public SpawnChildrenWithProps(int numberOfChildren, Props props) {
      this.numberOfChildren = numberOfChildren;
      this.props = props;
    }
  }

  public static class SpawnChildrenAnonymousWithProps implements Command {
    private final int numberOfChildren;
    private final Props props;

    public SpawnChildrenAnonymousWithProps(int numberOfChildren, Props props) {
      this.numberOfChildren = numberOfChildren;
      this.props = props;
    }
  }

  public interface Action {
  }

  private static Behavior<Action> childInitial = Behaviors.ignore();

  private static Props props = Props.empty().withDispatcherFromConfig("cat");

  private static Behavior<Command> behavior = Behaviors.receive(Command.class)
    .onMessage(SpawnChildren.class, (ctx, msg) -> {
      IntStream.range(0, msg.numberOfChildren).forEach(i -> {
        ctx.spawn(childInitial, "child" + i);
      });
      return Behaviors.same();
    })
    .onMessage(SpawnChildrenAnonymous.class, (ctx, msg) -> {
      IntStream.range(0, msg.numberOfChildren).forEach(i -> {
        ctx.spawnAnonymous(childInitial);
      });
      return Behaviors.same();
    })
    .onMessage(SpawnChildrenWithProps.class, (ctx, msg) -> {
      IntStream.range(0, msg.numberOfChildren).forEach(i -> {
        ctx.spawn(childInitial, "child" + i, msg.props);
      });
      return Behaviors.same();
    })
    .onMessage(SpawnChildrenAnonymousWithProps.class, (ctx, msg) -> {
      IntStream.range(0, msg.numberOfChildren).forEach(i -> {
        ctx.spawnAnonymous(childInitial, msg.props);
      });
      return Behaviors.same();
    })
    .onMessage(CreateMessageAdapter.class, (ctx, msg) -> {
      ctx.messageAdapter(msg.clazz, msg.f);
      return Behaviors.same();
    })
    .onMessage(SpawnWatchAndUnWatch.class, (ctx, msg) -> {
      ActorRef<Action> c = ctx.spawn(childInitial, msg.name);
      ctx.watch(c);
      ctx.unwatch(c);
      return Behaviors.same();
    })
    .onMessage(SpawnAndWatchWith.class, (ctx, msg) -> {
      ActorRef<Action> c = ctx.spawn(childInitial, msg.name);
      ctx.watchWith(c, msg);
      return Behaviors.same();
    })
    .onMessage(SpawnSession.class, (ctx, msg) -> {
      ActorRef<String> session = ctx.spawnAnonymous(Behaviors.receiveMessage( m -> {
        msg.sessionHandler.tell(m);
        return Behaviors.same();
      }));
      msg.replyTo.tell(session);
      return Behaviors.same();
    })
    .onMessage(KillSession.class, (ctx, msg) -> {
      ctx.stop(msg.session);
      msg.replyTo.tell(Done.getInstance());
      return Behaviors.same();
    })
    .build();


  @Test
  public void allowAssertionsOnEffectType() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildren(1));
    Effects.Spawned spawned = test.expectEffectClass(Effects.Spawned.class);
    assertEquals(spawned.childName(), "child0");
  }

  @Test
  public void allowExpectingNoEffects() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.expectEffect(EffectFactory.noEffects());
  }

  @Test
  public void allowsExpectingNoEffectByType() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.expectEffectClass(Effects.NoEffects.class);
  }

  @Test
  public void returnEffectsThatHaveTakenPlace() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    assertFalse(test.hasEffects());
    test.run(new SpawnChildrenAnonymous(1));
    assertTrue(test.hasEffects());
  }

  @Test
  @Ignore("Not supported for Java API")
  public void allowAssertionsUsingPartialFunctions() {
  }

  @Test
  public void spawnChildrenWithNoProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildren(2));
    List<Effect> allEffects = test.getAllEffects();
    assertEquals(
      Arrays.asList(EffectFactory.spawned(childInitial, "child0"), EffectFactory.spawned(childInitial, "child1", Props.empty())),
      allEffects
    );
  }

  @Test
  public void spawnChildrenWithProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildrenWithProps(1, props));
    assertEquals(props, test.expectEffectClass(Effects.Spawned.class).props());
  }

  @Test
  public void spawnAnonChildrenWithNoProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildrenAnonymous(2));
    List<Effect> allEffects = test.getAllEffects();
    assertEquals(
      Arrays.asList(EffectFactory.spawnedAnonymous(childInitial), EffectFactory.spawnedAnonymous(childInitial, Props.empty())),
      allEffects
    );
  }

  @Test
  public void spawnAnonChildrenWithProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildrenAnonymousWithProps(1, props));
    assertEquals(props, test.expectEffectClass(Effects.SpawnedAnonymous.class).props());
  }

  @Test
  public void createMessageAdapters() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    SpawnChildren adaptedMessage = new SpawnChildren(1);
    test.run(new CreateMessageAdapter(String.class, o -> adaptedMessage));
    Effects.MessageAdapter mAdapter = test.expectEffectClass(Effects.MessageAdapter.class);
    assertEquals(String.class, mAdapter.messageClass());
    assertEquals(adaptedMessage, mAdapter.adaptFunction().apply("anything"));
  }

  @Test
  public void recordWatching() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnWatchAndUnWatch("name"));
    ActorRef<Object> child = test.childInbox("name").getRef();
    test.expectEffectClass(Effects.Spawned.class);
    assertEquals(child, test.expectEffectClass(Effects.Watched.class).other());
    assertEquals(child, test.expectEffectClass(Effects.Unwatched.class).other());
  }

  @Test
  public void recordWatchWith() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnWatchAndUnWatch("name"));
    ActorRef<Object> child = test.childInbox("name").getRef();
    test.expectEffectClass(Effects.Spawned.class);
    assertEquals(child, test.expectEffectClass(Effects.Watched.class).other());
  }

  @Test
  public void allowRetrievingAndKilling() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    TestInbox<ActorRef<String>> i = TestInbox.create();
    TestInbox<String> h = TestInbox.create();
    test.run(new SpawnSession(i.getRef(), h.getRef()));

    ActorRef<String> sessionRef = i.receiveMessage();
    assertFalse(i.hasMessages());
    Effects.SpawnedAnonymous s = test.expectEffectClass(Effects.SpawnedAnonymous.class);
    assertEquals(sessionRef, s.ref());

    BehaviorTestKit<String> session = test.childTestKit(sessionRef);
    session.run("hello");
    assertEquals(Collections.singletonList("hello"), h.getAllReceived());

    TestInbox<Done> d = TestInbox.create();
    test.run(new KillSession(sessionRef, d.getRef()));

    assertEquals(Collections.singletonList(Done.getInstance()), d.getAllReceived());
    test.expectEffectClass(Effects.Stopped.class);
  }

}
