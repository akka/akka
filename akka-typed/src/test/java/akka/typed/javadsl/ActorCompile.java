/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.javadsl;

import akka.typed.*;
import static akka.typed.javadsl.Actor.*;

public class ActorCompile {
  
  interface MyMsg {}
  
  class MyMsgA implements MyMsg {
    final ActorRef<String> replyTo;

    public MyMsgA(ActorRef<String> replyTo) {
      this.replyTo = replyTo;
    }
  }

  class MyMsgB implements MyMsg {
    final String greeting;

    public MyMsgB(String greeting) {
      this.greeting = greeting;
    }
  }

  Behavior<MyMsg> actor1 = signalOrMessage((ctx, signal) -> same(),  (ctx, msg) -> stopped());
  Behavior<MyMsg> actor2 = stateful((ctx, msg) -> unhandled());
  Behavior<MyMsg> actor3 = stateless((ctx, msg) -> {});
  Behavior<MyMsg> actor4 = empty();
  Behavior<MyMsg> actor5 = ignore();
  Behavior<MyMsg> actor6 = tap((ctx, signal) -> {}, (ctx, msg) -> {}, actor5);
  Behavior<MyMsgA> actor7 = actor6.narrow();
  Behavior<MyMsg> actor8 = deferred(ctx -> {
    final ActorRef<MyMsg> self = ctx.getSelf();
    return monitor(self, ignore());
  });
  Behavior<MyMsg> actor9 = widened(actor7, pf -> pf.match(MyMsgA.class, x -> x));

  {
    Actor.<MyMsg>stateful((ctx, msg) -> {
      if (msg instanceof MyMsgA) {
        return stateless((ctx2, msg2) -> {
          if (msg2 instanceof MyMsgB) {
            ((MyMsgA) msg).replyTo.tell(((MyMsgB) msg2).greeting);
          }
        });
      } else return unhandled();
    });
  }
}
