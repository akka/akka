/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.stream.typed;

// #actor-sink-ref-with-backpressure
import akka.NotUsed;
import akka.actor.typed.ActorRef;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.typed.javadsl.ActorSink;
// #actor-sink-ref-with-backpressure

public class ActorSinkWithAckExample {

  // #actor-sink-ref-with-backpressure

  class Ack {}

  interface Protocol {}

  class Init implements Protocol {
    private final ActorRef<Ack> ack;

    public Init(ActorRef<Ack> ack) {
      this.ack = ack;
    }
  }

  class Message implements Protocol {
    private final ActorRef<Ack> ackTo;
    private final String msg;

    public Message(ActorRef<Ack> ackTo, String msg) {
      this.ackTo = ackTo;
      this.msg = msg;
    }
  }

  class Complete implements Protocol {}

  class Fail implements Protocol {
    private final Throwable ex;

    public Fail(Throwable ex) {
      this.ex = ex;
    }
  }
  // #actor-sink-ref-with-backpressure

  final ActorMaterializer mat = null;

  {
    // #actor-sink-ref-with-backpressure

    final ActorRef<Protocol> actor = null;

    final Sink<String, NotUsed> sink =
        ActorSink.actorRefWithBackpressure(
            actor, Message::new, Init::new, new Ack(), new Complete(), Fail::new);

    Source.single("msg1").runWith(sink, mat);
    // #actor-sink-ref-with-backpressure
  }
}
