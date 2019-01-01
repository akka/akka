/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.actor.typed.Behavior;

import static org.junit.Assert.assertEquals;

/**
 * Test creating [[MutableActor]]s using [[ReceiveBuilder]]
 */
public class ReceiveBuilderTest extends JUnitSuite {

  @Test
  public void testMutableCounter() {
    Behavior<BehaviorBuilderTest.CounterMessage> mutable = Behaviors.setup(context -> new AbstractBehavior<BehaviorBuilderTest.CounterMessage>() {
      int currentValue = 0;

      private Behavior<BehaviorBuilderTest.CounterMessage> receiveIncrease(BehaviorBuilderTest.Increase message) {
        currentValue++;
        return this;
      }

      private Behavior<BehaviorBuilderTest.CounterMessage> receiveGet(BehaviorBuilderTest.Get get) {
        get.sender.tell(new BehaviorBuilderTest.Got(currentValue));
        return this;
      }

      @Override
      public Receive<BehaviorBuilderTest.CounterMessage> createReceive() {
        return receiveBuilder()
          .onMessage(BehaviorBuilderTest.Increase.class, this::receiveIncrease)
          .onMessage(BehaviorBuilderTest.Get.class, this::receiveGet)
          .build();
      }
    });
  }

  private static class MyAbstractBehavior extends AbstractBehavior<BehaviorBuilderTest.CounterMessage> {
    private int value;

    public MyAbstractBehavior(int initialValue) {
      super();
      this.value = initialValue;
    }

    @Override
    public Receive<BehaviorBuilderTest.CounterMessage> createReceive() {
      assertEquals(42, value);
      return receiveBuilder().build();
    }
  }

  @Test
  public void testInitializationOrder() throws Exception {
    MyAbstractBehavior mutable = new MyAbstractBehavior(42);
    assertEquals(Behaviors.unhandled(), mutable.receive(null, new BehaviorBuilderTest.Increase()));
  }
}
