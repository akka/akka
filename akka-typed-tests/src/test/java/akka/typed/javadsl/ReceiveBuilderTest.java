/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.javadsl;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.typed.Behavior;

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
}
