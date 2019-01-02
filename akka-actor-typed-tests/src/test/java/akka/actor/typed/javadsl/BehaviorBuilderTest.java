/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.ActorRef;

import java.util.ArrayList;

import static akka.actor.typed.javadsl.Behaviors.same;
import static akka.actor.typed.javadsl.Behaviors.stopped;

/**
 * Test creating [[Behavior]]s using [[BehaviorBuilder]]
 */
public class BehaviorBuilderTest extends JUnitSuite {
    interface Message {
    }

    static final class One implements Message {
        public String foo() {
          return "Bar";
        }
    }
    static final class MyList<T> extends ArrayList<T> implements Message {
    };

    @Test
    public void shouldCompile() {
      Behavior<Message> b = Behaviors.receive(Message.class)
        .onMessage(One.class, (context, o) -> {
          o.foo();
          return same();
        })
        .onMessage(One.class, o -> o.foo().startsWith("a"), (context, o) -> same())
        .onMessageUnchecked(MyList.class, (ActorContext<Message> context, MyList<String> l) -> {
          String first = l.get(0);
          return Behaviors.<Message>same();
        })
        .onSignal(Terminated.class, (context, t) -> {
          System.out.println("Terminating along with " + t.getRef());
          return stopped();
        })
        .build();
    }

    interface CounterMessage {};
    static final class Increase implements CounterMessage {};
    static final class Get implements CounterMessage {
      final ActorRef<Got> sender;
      public Get(ActorRef<Got> sender) {
        this.sender = sender;
      }
    };
    static final class Got {
      final int n;
      public Got(int n) {
        this.n = n;
      }
    }

    public Behavior<CounterMessage> immutableCounter(int currentValue) {
      return Behaviors.receive(CounterMessage.class)
          .onMessage(Increase.class, (context, o) -> {
            return immutableCounter(currentValue + 1);
          })
          .onMessage(Get.class, (context, o) -> {
            o.sender.tell(new Got(currentValue));
            return same();
          })
          .build();
    }

    @Test
    public void testImmutableCounter() {
      Behavior<CounterMessage> immutable = immutableCounter(0);
    }

}
