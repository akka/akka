/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.javadsl;

import akka.typed.Behavior;
import akka.typed.Terminated;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import static akka.typed.javadsl.Actor.same;
import static akka.typed.javadsl.Actor.stopped;

public class BehaviorBuilderTest extends JUnitSuite {
    interface Message {
    }

    static class One implements Message {
        public void foo() {}
    }

    @Test
    public void shouldCompile() {
        Behavior<Message> b = BehaviorBuilder.<Message>create()
                .message(One.class, (ctx, o) -> {
                    o.foo();
                    return same();
                })
                .signal(Terminated.class, (ctx, t) -> {
                    System.out.println("Terminating along with " + t.ref());
                    return stopped();
                })
                .build();
    }
}
