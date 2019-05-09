/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class ActorFlowCompileTest {

  interface Protocol {}

  class Init implements Protocol {}

  class Msg implements Protocol {}

  class Complete implements Protocol {}

  class Failure implements Protocol {
    public Exception ex;
  }

  {
    final ActorSystem<String> system = null;
    final ActorMaterializer mat = ActorMaterializerFactory.create(system);
  }

  static
  // #ask-actor
  class AskMe {
    final String payload;
    final ActorRef<String> replyTo;

    AskMe(String payload, ActorRef<String> replyTo) {
      this.payload = payload;
      this.replyTo = replyTo;
    }
  }

  // #ask-actor

  {
    final ActorRef<AskMe> ref = null;

    // #ask
    Duration timeout = Duration.of(1, ChronoUnit.SECONDS);

    Source.repeat("hello").via(ActorFlow.ask(ref, timeout, AskMe::new)).to(Sink.ignore());

    Source.repeat("hello")
        .via(
            ActorFlow.<String, AskMe, String>ask(
                ref, timeout, (msg, replyTo) -> new AskMe(msg, replyTo)))
        .to(Sink.ignore());
    // #ask
  }
}
