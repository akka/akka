/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.RecoveryCompleted;
import akka.testkit.EventFilter;
import akka.testkit.TestEvent;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.atomic.AtomicInteger;

public class LoggerSourceTest extends JUnitSuite {

  private static final Config config =
      ConfigFactory.parseString(
          "akka.loggers = [akka.testkit.TestEventListener] \n"
              + "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\" \n");

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  private static final AtomicInteger idCounter = new AtomicInteger(0);

  public static PersistenceId nextId() {
    return new PersistenceId("" + idCounter.incrementAndGet());
  }

  static class LoggingBehavior extends EventSourcedBehavior<String, String, String> {

    private final ActorContext<String> ctx;

    public LoggingBehavior(PersistenceId persistenceId, ActorContext<String> ctx) {
      super(persistenceId);
      this.ctx = ctx;
    }

    @Override
    public String emptyState() {
      return "";
    }

    @Override
    public CommandHandler<String, String, String> commandHandler() {
      return newCommandHandlerBuilder()
          .forAnyState()
          .onCommand(str -> str.equals("stop"), (cmd, state) -> Effect().stop())
          .onAnyCommand(
              (cmd, state) -> {
                ctx.getLog().info("command-received");
                return Effect().persist("evt");
              });
    }

    @Override
    public EventHandler<String, String> eventHandler() {
      return newEventHandlerBuilder()
          .forAnyState()
          .onAnyEvent(
              (evt, state) -> {
                ctx.getLog().info("event-received");
                return evt + state;
              });
    }

    @Override
    public SignalHandler signalHandler() {
      return newSignalHandlerBuilder()
          .onSignal(
              RecoveryCompleted.instance(),
              (signal) -> {
                ctx.getLog().info("recovery-completed");
              })
          .build();
    }
  }

  public Behavior<String> behavior =
      Behaviors.setup(
          (ctx) -> {
            ctx.getLog().info("setting-up-behavior");
            return new LoggingBehavior(nextId(), ctx);
          });

  public LoggerSourceTest() {
    // FIXME ##24348 silence logging in a proper way
    akka.actor.typed.javadsl.Adapter.toUntyped(testKit.system())
        .eventStream()
        .publish(
            new TestEvent.Mute(
                akka.japi.Util.immutableSeq(
                    new EventFilter[] {
                      EventFilter.warning(null, null, "No default snapshot store", null, 1)
                    })));
  }

  @Test
  public void verifyLoggerClass() {
    ActorRef<String> ref = testKit.spawn(behavior);
    ref.tell("command");
    // no Java testkit support for custom event filters to look at the logger class
    // so this is a manual-occular test for now (additionally it requires a small change to
    // stdoutlogger to print log event class name)
    // FIXME #24348: eventfilter support in typed testkit
    TestProbe<Object> probe = testKit.createTestProbe();
    ref.tell("stop");
    probe.expectTerminated(ref);
  }
}
