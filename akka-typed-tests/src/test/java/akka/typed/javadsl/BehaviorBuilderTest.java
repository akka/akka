/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.javadsl;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.typed.Behavior;
import akka.typed.Terminated;
import akka.typed.ActorRef;

import akka.typed.javadsl.Actor.MutableBehavior;
import akka.typed.javadsl.Actor.Receive;

import java.util.ArrayList;

import static akka.typed.javadsl.Actor.same;
import static akka.typed.javadsl.Actor.stopped;

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
      Behavior<Message> b = Actor.immutable(Message.class)
              .onMessage(One.class, (ctx, o) -> {
                o.foo();
                return same();
              })
              .onMessage(One.class, o -> o.foo().startsWith("a"), (ctx, o) -> same())
              .onMessageUnchecked(MyList.class, (ActorContext<Message> ctx, MyList<String> l) -> {
                String first = l.get(0);
                return Actor.<Message>same();
              })
              .onSignal(Terminated.class, (ctx, t) -> {
                System.out.println("Terminating along with " + t.ref());
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
      return Actor.immutable(CounterMessage.class)
          .onMessage(Increase.class, (ctx, o) -> {
            return immutableCounter(currentValue + 1);
          })
          .onMessage(Get.class, (ctx, o) -> {
            o.sender.tell(new Got(currentValue));
            return same();
          })
          .build();
    }

    @Test
    public void testImmutableCounter() {
      Behavior<CounterMessage> immutable = immutableCounter(0);
    }

    static final class MutableStateHolder {
      int currentValue = 0;
    }

    @Test
    public void testMutableCounter() {
      Behavior<CounterMessage> mutable = Actor.mutable(ctx -> new MutableBehavior<CounterMessage>() {
        MutableStateHolder state = new MutableStateHolder();

        @Override
        public Receive<CounterMessage> createReceive() {
          return receiveBuilder()
            .onMessage(Increase.class, o -> {
              state.currentValue++;
              return this;
            })
            .onMessage(Get.class, o -> {
              o.sender.tell(new Got(state.currentValue));
              return this;
            })
            .build();
        }
      });
    }
}
