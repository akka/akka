/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl;

import akka.Done;
import akka.actor.testkit.typed.CapturedLogEvent;
import akka.actor.testkit.typed.Effect;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.Behaviors;
import akka.event.Logging;
import org.junit.Ignore;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BehaviorTestKitTest extends JUnitSuite {

  public interface Command {}

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

  public static class Log implements Command {
    private final String what;

    public Log(String what) {
      this.what = what;
    }
  }

  public interface Action {}

  private static Behavior<Action> childInitial = Behaviors.ignore();

  private static Props props = Props.empty().withDispatcherFromConfig("cat");

  private static Behavior<Command> behavior =
      Behaviors.receive(Command.class)
          .onMessage(
              SpawnChildren.class,
              (context, message) -> {
                IntStream.range(0, message.numberOfChildren)
                    .forEach(
                        i -> {
                          context.spawn(childInitial, "child" + i);
                        });
                return Behaviors.same();
              })
          .onMessage(
              SpawnChildrenAnonymous.class,
              (context, message) -> {
                IntStream.range(0, message.numberOfChildren)
                    .forEach(
                        i -> {
                          context.spawnAnonymous(childInitial);
                        });
                return Behaviors.same();
              })
          .onMessage(
              SpawnChildrenWithProps.class,
              (context, message) -> {
                IntStream.range(0, message.numberOfChildren)
                    .forEach(
                        i -> {
                          context.spawn(childInitial, "child" + i, message.props);
                        });
                return Behaviors.same();
              })
          .onMessage(
              SpawnChildrenAnonymousWithProps.class,
              (context, message) -> {
                IntStream.range(0, message.numberOfChildren)
                    .forEach(
                        i -> {
                          context.spawnAnonymous(childInitial, message.props);
                        });
                return Behaviors.same();
              })
          .onMessage(
              CreateMessageAdapter.class,
              (context, message) -> {
                context.messageAdapter(message.clazz, message.f);
                return Behaviors.same();
              })
          .onMessage(
              SpawnWatchAndUnWatch.class,
              (context, message) -> {
                ActorRef<Action> c = context.spawn(childInitial, message.name);
                context.watch(c);
                context.unwatch(c);
                return Behaviors.same();
              })
          .onMessage(
              SpawnAndWatchWith.class,
              (context, message) -> {
                ActorRef<Action> c = context.spawn(childInitial, message.name);
                context.watchWith(c, message);
                return Behaviors.same();
              })
          .onMessage(
              SpawnSession.class,
              (context, message) -> {
                ActorRef<String> session =
                    context.spawnAnonymous(
                        Behaviors.receiveMessage(
                            m -> {
                              message.sessionHandler.tell(m);
                              return Behaviors.same();
                            }));
                message.replyTo.tell(session);
                return Behaviors.same();
              })
          .onMessage(
              KillSession.class,
              (context, message) -> {
                context.stop(message.session);
                message.replyTo.tell(Done.getInstance());
                return Behaviors.same();
              })
          .onMessage(
              Log.class,
              (context, message) -> {
                context.getLog().info(message.what);
                return Behaviors.same();
              })
          .build();

  @Test
  public void allowAssertionsOnEffectType() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildren(1));
    Effect.Spawned spawned = test.expectEffectClass(Effect.Spawned.class);
    assertEquals(spawned.childName(), "child0");
  }

  @Test
  public void allowExpectingNoEffects() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.expectEffect(Effects.noEffects());
  }

  @Test
  public void allowsExpectingNoEffectByType() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.expectEffectClass(Effect.NoEffects.class);
  }

  @Test
  public void allowRetrieveAllLogs() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    String what = "Hello!";
    test.run(new Log(what));
    final List<CapturedLogEvent> allLogEntries = test.getAllLogEntries();
    assertEquals(1, allLogEntries.size());
    assertEquals(new CapturedLogEvent(Logging.InfoLevel(), what), allLogEntries.get(0));
  }

  @Test
  public void allowClearLogs() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    String what = "Hello!";
    test.run(new Log(what));
    assertEquals(1, test.getAllLogEntries().size());
    test.clearLog();
    assertEquals(0, test.getAllLogEntries().size());
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
  public void allowAssertionsUsingPartialFunctions() {}

  @Test
  public void spawnChildrenWithNoProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildren(2));
    List<Effect> allEffects = test.getAllEffects();
    assertEquals(
        Arrays.asList(
            Effects.spawned(childInitial, "child0"),
            Effects.spawned(childInitial, "child1", Props.empty())),
        allEffects);
  }

  @Test
  public void spawnChildrenWithProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildrenWithProps(1, props));
    assertEquals(props, test.expectEffectClass(Effect.Spawned.class).props());
  }

  @Test
  public void spawnAnonChildrenWithNoProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildrenAnonymous(2));
    List<Effect> allEffects = test.getAllEffects();
    assertEquals(
        Arrays.asList(
            Effects.spawnedAnonymous(childInitial),
            Effects.spawnedAnonymous(childInitial, Props.empty())),
        allEffects);
  }

  @Test
  public void spawnAnonChildrenWithProps() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnChildrenAnonymousWithProps(1, props));
    assertEquals(props, test.expectEffectClass(Effect.SpawnedAnonymous.class).props());
  }

  @Test
  public void createMessageAdapters() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    SpawnChildren adaptedMessage = new SpawnChildren(1);
    test.run(new CreateMessageAdapter(String.class, o -> adaptedMessage));
    Effect.MessageAdapter mAdapter = test.expectEffectClass(Effect.MessageAdapter.class);
    assertEquals(String.class, mAdapter.messageClass());
    assertEquals(adaptedMessage, mAdapter.adaptFunction().apply("anything"));
  }

  @Test
  public void recordWatching() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnWatchAndUnWatch("name"));
    ActorRef<Object> child = test.childInbox("name").getRef();
    test.expectEffectClass(Effect.Spawned.class);
    assertEquals(child, test.expectEffectClass(Effect.Watched.class).other());
    assertEquals(child, test.expectEffectClass(Effect.Unwatched.class).other());
  }

  @Test
  public void recordWatchWith() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    test.run(new SpawnWatchAndUnWatch("name"));
    ActorRef<Object> child = test.childInbox("name").getRef();
    test.expectEffectClass(Effect.Spawned.class);
    assertEquals(child, test.expectEffectClass(Effect.Watched.class).other());
  }

  @Test
  public void allowRetrievingAndKilling() {
    BehaviorTestKit<Command> test = BehaviorTestKit.create(behavior);
    TestInbox<ActorRef<String>> i = TestInbox.create();
    TestInbox<String> h = TestInbox.create();
    test.run(new SpawnSession(i.getRef(), h.getRef()));

    ActorRef<String> sessionRef = i.receiveMessage();
    assertFalse(i.hasMessages());
    Effect.SpawnedAnonymous s = test.expectEffectClass(Effect.SpawnedAnonymous.class);
    assertEquals(sessionRef, s.ref());

    BehaviorTestKit<String> session = test.childTestKit(sessionRef);
    session.run("hello");
    assertEquals(Collections.singletonList("hello"), h.getAllReceived());

    TestInbox<Done> d = TestInbox.create();
    test.run(new KillSession(sessionRef, d.getRef()));

    assertEquals(Collections.singletonList(Done.getInstance()), d.getAllReceived());
    test.expectEffectClass(Effect.Stopped.class);
  }
}
