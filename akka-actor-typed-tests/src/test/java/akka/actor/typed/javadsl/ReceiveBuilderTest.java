/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import akka.actor.typed.Behavior;

import static akka.actor.typed.javadsl.Behaviors.same;
import static org.junit.Assert.assertEquals;

/** Test creating [[MutableActor]]s using [[ReceiveBuilder]] */
public class ReceiveBuilderTest extends JUnitSuite {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testMutableCounter() {
    Behavior<BehaviorBuilderTest.CounterMessage> mutable =
        Behaviors.setup(
            context ->
                new AbstractBehavior<BehaviorBuilderTest.CounterMessage>() {
                  int currentValue = 0;

                  private Behavior<BehaviorBuilderTest.CounterMessage> receiveIncrease(
                      BehaviorBuilderTest.Increase message) {
                    currentValue++;
                    return this;
                  }

                  private Behavior<BehaviorBuilderTest.CounterMessage> receiveGet(
                      BehaviorBuilderTest.Get get) {
                    get.sender.tell(new BehaviorBuilderTest.Got(currentValue));
                    return this;
                  }

                  @Override
                  public Receive<BehaviorBuilderTest.CounterMessage> createReceive() {
                    return newReceiveBuilder()
                        .onMessage(BehaviorBuilderTest.Increase.class, this::receiveIncrease)
                        .onMessage(BehaviorBuilderTest.Get.class, this::receiveGet)
                        .build();
                  }
                });
  }

  private static class MyAbstractBehavior
      extends AbstractBehavior<BehaviorBuilderTest.CounterMessage> {
    private int value;

    public MyAbstractBehavior(int initialValue) {
      super();
      this.value = initialValue;
    }

    @Override
    public Receive<BehaviorBuilderTest.CounterMessage> createReceive() {
      assertEquals(42, value);
      return newReceiveBuilder().build();
    }
  }

  @Test
  public void testInitializationOrder() throws Exception {
    MyAbstractBehavior mutable = new MyAbstractBehavior(42);
    assertEquals(Behaviors.unhandled(), mutable.receive(null, new BehaviorBuilderTest.Increase()));
  }

  @Test
  public void caseSelectedInOrderAdded() {
    final TestProbe<Object> probe = testKit.createTestProbe();
    Behavior<Object> behavior =
        ReceiveBuilder.<Object>create()
            .onMessage(
                String.class,
                msg -> {
                  probe.ref().tell("handler 1: " + msg);
                  return Behaviors.same();
                })
            .onMessage(
                String.class,
                msg -> {
                  probe.ref().tell("handler 2: " + msg);
                  return Behaviors.same();
                })
            .build();
    ActorRef<Object> ref = testKit.spawn(behavior);
    ref.tell("message");
    probe.expectMessage("handler 1: message");
  }

  @Test
  public void applyPredicate() {
    final TestProbe<Object> probe = testKit.createTestProbe();
    Behavior<Object> behavior =
        ReceiveBuilder.create()
            .onMessage(
                String.class,
                msg -> "other".equals(msg),
                msg -> {
                  probe.ref().tell("handler 1: " + msg);
                  return Behaviors.same();
                })
            .onMessage(
                String.class,
                msg -> {
                  probe.ref().tell("handler 2: " + msg);
                  return Behaviors.same();
                })
            .build();
    ActorRef<Object> ref = testKit.spawn(behavior);
    ref.tell("message");
    probe.expectMessage("handler 2: message");
  }

  @Test
  public void catchAny() {
    final TestProbe<Object> probe = testKit.createTestProbe();
    Behavior<Object> behavior =
        ReceiveBuilder.create()
            .onAnyMessage(
                msg -> {
                  probe.ref().tell(msg);
                  return same();
                })
            .build();
    ActorRef<Object> ref = testKit.spawn(behavior);
    ref.tell("message");
    probe.expectMessage("message");
  }
}
