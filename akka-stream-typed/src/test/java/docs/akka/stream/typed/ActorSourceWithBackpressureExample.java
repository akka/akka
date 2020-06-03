/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.stream.typed;

// #sample
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.stream.CompletionStrategy;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.typed.javadsl.ActorSource;

import java.util.Optional;

// #sample

public class ActorSourceWithBackpressureExample {

  // #sample
  /** Signals demand from the stream */
  final class Ack {}

  /** The Protocol to feed the stream */
  interface Protocol {}

  class Message implements Protocol {
    private final String msg;

    public Message(String msg) {
      this.msg = msg;
    }

    @Override
    public String toString() {
      return "Message(" + msg + ")";
    }
  }

  class Complete implements Protocol {}

  class Fail implements Protocol {
    private final Exception ex;

    public Fail(Exception ex) {
      this.ex = ex;
    }
  }

  class Sender extends AbstractBehavior<Ack> {

    private int counter = 0;
    private ActorRef<Protocol> streamSource;

    private Sender(ActorContext<Ack> context) {
      super(context);
    }

    @Override
    public Receive<Ack> createReceive() {
      Source<Protocol, ActorRef<Protocol>> source =
          ActorSource.actorRefWithBackpressure(
              // get demand signalled to this actor receiving Ack
              getContext().getSelf(),
              new Ack(),
              // complete when we send Complete
              (msg) -> {
                if (msg instanceof Complete) return Optional.of(CompletionStrategy.draining());
                else return Optional.empty();
              },
              (msg) -> {
                if (msg instanceof Fail) return Optional.of(((Fail) msg).ex);
                else return Optional.empty();
              });

      streamSource =
          source.to(Sink.foreach(msg -> System.out.println(msg))).run(getContext().getSystem());

      streamSource.tell(new Message("first"));

      return newReceiveBuilder().onMessage(Ack.class, this::onAck).build();
    }

    private Behavior<Ack> onAck(Ack message) {
      if (counter < 5) {
        streamSource.tell(new Message(String.valueOf(counter)));
        counter++;
        return this;
      } else {
        streamSource.tell(new Complete());
        return Behaviors.stopped();
      }
    }
  }

  // will print:
  // Message(first)
  // Message(0)
  // Message(1)
  // Message(2)
  // Message(3)
  // Message(4)
  // #sample

}
