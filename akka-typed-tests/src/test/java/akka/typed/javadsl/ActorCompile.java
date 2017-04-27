/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.javadsl;

import akka.typed.*;
import akka.typed.ActorContext;

import static akka.typed.javadsl.Actor.*;

public class ActorCompile {

  interface MyMsg {}

  static class MyMsgA implements MyMsg {
    final ActorRef<String> replyTo;

    public MyMsgA(ActorRef<String> replyTo) {
      this.replyTo = replyTo;
    }
  }

  static class MyMsgB implements MyMsg {
    final String greeting;

    public MyMsgB(String greeting) {
      this.greeting = greeting;
    }
  }

  Behavior<MyMsg> actor1 = immutable((ctx, msg) -> stopped(), (ctx, signal) -> same());
  Behavior<MyMsg> actor2 = immutable((ctx, msg) -> unhandled());
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
    Actor.<MyMsg>immutable((ctx, msg) -> {
      if (msg instanceof MyMsgA) {
        return immutable((ctx2, msg2) -> {
          if (msg2 instanceof MyMsgB) {
            ((MyMsgA) msg).replyTo.tell(((MyMsgB) msg2).greeting);

            @SuppressWarnings("unused")
            ActorRef<String> adapter = ctx2.spawnAdapter(s -> new MyMsgB(s.toUpperCase()));
          }
          return same();
        });
      } else return unhandled();
    });
  }


  static class MyBehavior extends ExtensibleBehavior<MyMsg> {

    @Override
    public Behavior<MyMsg> receiveSignal(ActorContext<MyMsg> ctx, Signal msg) throws Exception {
      return this;
    }

    @Override
    public Behavior<MyMsg> receiveMessage(ActorContext<MyMsg> ctx, MyMsg msg) throws Exception {
      @SuppressWarnings("unused")
      ActorRef<String> adapter = ctx.asJava().spawnAdapter(s -> new MyMsgB(s.toUpperCase()));
      return this;
    }

  }


}
