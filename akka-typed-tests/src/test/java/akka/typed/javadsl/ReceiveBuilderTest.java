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

  static final class MutableStateHolder {
    int currentValue = 0;
  }

  @Test
  public void testMutableCounter() {
    Behavior<BehaviorBuilderTest.CounterMessage> mutable = Actor.mutable(ctx -> new Actor.MutableBehavior<BehaviorBuilderTest.CounterMessage>() {
      MutableStateHolder state = new MutableStateHolder();

      @Override
      public Actor.Receive<BehaviorBuilderTest.CounterMessage> createReceive() {
        return receiveBuilder()
          .onMessage(BehaviorBuilderTest.Increase.class, o -> {
            state.currentValue++;
            return this;
          })
          .onMessage(BehaviorBuilderTest.Get.class, o -> {
            o.sender.tell(new BehaviorBuilderTest.Got(state.currentValue));
            return this;
          })
          .build();
      }
    });
  }
}
