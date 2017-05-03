/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.javadsl;

import akka.typed.Behavior;
import akka.typed.Terminated;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

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
              .message(One.class, (ctx, o) -> {
                o.foo();
                return same();
              })
              .message(One.class, o -> o.foo().startsWith("a"), (ctx, o) -> same())
              .messageUnchecked(MyList.class, (ActorContext<Message> ctx, MyList<String> l) -> {
                String first = l.get(0);
                return Actor.<Message>same();
              })
              .signal(Terminated.class, (ctx, t) -> {
                System.out.println("Terminating along with " + t.ref());
                return stopped();
              })
              .build();
    }
}
