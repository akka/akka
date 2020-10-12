/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.javadsl;

import akka.NotUsed;
// #ask-actor
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.typed.javadsl.ActorFlow;

// #ask-actor
import java.time.Duration;

public class ActorFlowCompileTest {

  final ActorSystem<String> system = null;

  static
  // #ask-actor
  class Asking {
    final String payload;
    final ActorRef<Reply> replyTo;

    public Asking(String payload, ActorRef<Reply> replyTo) {
      this.payload = payload;
      this.replyTo = replyTo;
    }
  }

  // #ask-actor
  static
  // #ask-actor
  class Reply {
    public final String msg;

    public Reply(String msg) {
      this.msg = msg;
    }
  }

  // #ask-actor

  {
    // #ask
    final ActorRef<Asking> actorRef = // ???
        // #ask
        null;

    // #ask
    Duration timeout = Duration.ofSeconds(1);

    // method reference notation
    Flow<String, Reply, NotUsed> askFlow = ActorFlow.ask(actorRef, timeout, Asking::new);

    // explicit creation of the sent message
    Flow<String, Reply, NotUsed> askFlowExplicit =
        ActorFlow.ask(actorRef, timeout, (msg, replyTo) -> new Asking(msg, replyTo));

    Source.repeat("hello").via(askFlow).map(reply -> reply.msg).runWith(Sink.seq(), system);
    // #ask
  }
}
