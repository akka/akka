/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.typed.*;
import akka.actor.typed.ActorContext;

import java.time.Duration;

import static akka.actor.typed.javadsl.Behaviors.*;


@SuppressWarnings("unused")
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

  Behavior<MyMsg> actor1 = Behaviors.receive((ctx, msg) -> stopped(), (ctx, signal) -> same());
  Behavior<MyMsg> actor2 = Behaviors.receive((ctx, msg) -> unhandled());
  Behavior<MyMsg> actor4 = empty();
  Behavior<MyMsg> actor5 = ignore();
  Behavior<MyMsg> actor6 = tap((ctx, signal) -> {}, (ctx, msg) -> {}, actor5);
  Behavior<MyMsgA> actor7 = actor6.narrow();
  Behavior<MyMsg> actor8 = setup(ctx -> {
    final ActorRef<MyMsg> self = ctx.getSelf();
    return monitor(self, ignore());
  });
  Behavior<MyMsg> actor9 = widened(actor7, pf -> pf.match(MyMsgA.class, x -> x));
  Behavior<MyMsg> actor10 = Behaviors.receive((ctx, msg) -> stopped(actor4), (ctx, signal) -> same());

  ActorSystem<MyMsg> system = ActorSystem.create(actor1, "Sys");

  {
    Behaviors.<MyMsg>receive((ctx, msg) -> {
      if (msg instanceof MyMsgA) {
        return Behaviors.receive((ctx2, msg2) -> {
          if (msg2 instanceof MyMsgB) {
            ((MyMsgA) msg).replyTo.tell(((MyMsgB) msg2).greeting);

            ActorRef<String> adapter = ctx2.messageAdapter(String.class, s -> new MyMsgB(s.toUpperCase()));
          }
          return same();
        });
      } else return unhandled();
    });
  }

  {
    Behavior<MyMsg> b = Behaviors.withTimers(timers -> {
      timers.startPeriodicTimer("key", new MyMsgB("tick"), Duration.ofSeconds(1));
      return Behaviors.ignore();
    });
  }


  static class MyBehavior extends ExtensibleBehavior<MyMsg> {

    @Override
    public Behavior<MyMsg> receiveSignal(ActorContext<MyMsg> ctx, Signal msg) throws Exception {
      return this;
    }

    @Override
    public Behavior<MyMsg> receive(ActorContext<MyMsg> ctx, MyMsg msg) throws Exception {
      ActorRef<String> adapter = ctx.asJava().messageAdapter(String.class, s -> new MyMsgB(s.toUpperCase()));
      return this;
    }

  }

  // SupervisorStrategy
  {
    SupervisorStrategy strategy1 = SupervisorStrategy.restart();
    SupervisorStrategy strategy2 = SupervisorStrategy.restart().withLoggingEnabled(false);
    SupervisorStrategy strategy3 = SupervisorStrategy.resume();
    SupervisorStrategy strategy4 =
      SupervisorStrategy.restartWithLimit(3, Duration.ofSeconds(1));

    SupervisorStrategy strategy5 =
      SupervisorStrategy.restartWithBackoff(
        Duration.ofMillis(200),
        Duration.ofSeconds(10),
        0.1);

    BackoffSupervisorStrategy strategy6 =
        SupervisorStrategy.restartWithBackoff(
            Duration.ofMillis(200),
            Duration.ofSeconds(10),
          0.1);
    SupervisorStrategy strategy7 = strategy6.withResetBackoffAfter(Duration.ofSeconds(2));

    Behavior<MyMsg> behv =
      Behaviors.supervise(
        Behaviors.supervise(Behaviors.<MyMsg>ignore()).onFailure(IllegalStateException.class, strategy6)
      ).onFailure(RuntimeException.class, strategy1);
  }


}
