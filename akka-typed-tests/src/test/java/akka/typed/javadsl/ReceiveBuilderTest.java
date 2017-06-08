/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.javadsl;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.typed.Behavior;

import static org.junit.Assert.assertEquals;

/**
 * Test creating [[MutableActor]]s using [[ReceiveBuilder]]
 */
public class ReceiveBuilderTest extends JUnitSuite {

  @Test
  public void testMutableCounter() {
    Behavior<BehaviorBuilderTest.CounterMessage> mutable = Actor.mutable(ctx -> new Actor.MutableBehavior<BehaviorBuilderTest.CounterMessage>() {
      int currentValue = 0;

      private Behavior<BehaviorBuilderTest.CounterMessage> receiveIncrease(BehaviorBuilderTest.Increase msg) {
        currentValue++;
        return this;
      }

      private Behavior<BehaviorBuilderTest.CounterMessage> receiveGet(BehaviorBuilderTest.Get get) {
        get.sender.tell(new BehaviorBuilderTest.Got(currentValue));
        return this;
      }

      @Override
      public Actor.Receive<BehaviorBuilderTest.CounterMessage> createReceive() {
        return receiveBuilder()
          .onMessage(BehaviorBuilderTest.Increase.class, this::receiveIncrease)
          .onMessage(BehaviorBuilderTest.Get.class, this::receiveGet)
          .build();
      }
    });
  }

  private static class MyMutableBehavior extends Actor.MutableBehavior<BehaviorBuilderTest.CounterMessage> {
    private int value;

    public MyMutableBehavior(int initialValue) {
      super();
      this.value = initialValue;
    }

    @Override
    public Actor.Receive<BehaviorBuilderTest.CounterMessage> createReceive() {
      assertEquals(42, value);
      return receiveBuilder().build();
    }
  }

  @Test
  public void testInitializationOrder() throws Exception {
    MyMutableBehavior mutable = new MyMutableBehavior(42);
    assertEquals(Actor.unhandled(), mutable.receiveMessage(null, new BehaviorBuilderTest.Increase()));
  }
}
