/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package doc;

import java.util.concurrent.TimeUnit;

import akka.japi.pf.ReceiveBuilder;
import scala.Option;
import scala.PartialFunction;
import scala.concurrent.duration.Duration;

import akka.actor.*;
import akka.persistence.*;
import scala.runtime.BoxedUnit;

import static java.util.Arrays.asList;

public class LambdaPersistenceDocTest {

  public interface ProcessorMethods {
    //#processor-id
    public String processorId();
    //#processor-id
    //#recovery-status
    public boolean recoveryRunning();
    public boolean recoveryFinished();
    //#recovery-status
    //#current-message
    public Persistent getCurrentPersistentMessage();
    //#current-message
  }

  static Object o1 = new Object() {
    //#definition
    class MyProcessor extends AbstractProcessor {
      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(Persistent.class, p -> {
              // message successfully written to journal
              Object payload = p.payload();
              Long sequenceNr = p.sequenceNr();
              // ...
            }).
            match(PersistenceFailure.class, failure -> {
              // message failed to be written to journal
              Object payload = failure.payload();
              Long sequenceNr = failure.sequenceNr();
              Throwable cause = failure.cause();
              // ...
            }).
            matchAny(otherwise -> {
              // message not written to journal
            }).build();
      }
    }
    //#definition

    class MyActor extends AbstractActor {
      ActorRef processor;

      public MyActor() {
        //#usage
        processor = context().actorOf(Props.create(MyProcessor.class), "myProcessor");

        processor.tell(Persistent.create("foo"), null);
        processor.tell("bar", null);
        //#usage
      }

      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(Persistent.class, received -> {/* ... */}).build();
      }

      private void recover() {
        //#recover-explicit
        processor.tell(Recover.create(), null);
        //#recover-explicit
      }
    }
  };

  static Object o2 = new Object() {
    abstract class MyProcessor1 extends AbstractProcessor {
      //#recover-on-start-disabled
      @Override
      public void preStart() {}
      //#recover-on-start-disabled

      //#recover-on-restart-disabled
      @Override
      public void preRestart(Throwable reason, Option<Object> message) {}
      //#recover-on-restart-disabled
    }

    abstract class MyProcessor2 extends AbstractProcessor {
      //#recover-on-start-custom
      @Override
      public void preStart() {
        self().tell(Recover.create(457L), null);
      }
      //#recover-on-start-custom
    }

    abstract class MyProcessor3 extends AbstractProcessor {
      //#deletion
      @Override
      public void preRestart(Throwable reason, Option<Object> message) {
        if (message.isDefined() && message.get() instanceof Persistent) {
          deleteMessage(((Persistent) message.get()).sequenceNr());
        }
        super.preRestart(reason, message);
      }
      //#deletion
    }

    class MyProcessor4 extends AbstractProcessor implements ProcessorMethods {
      //#processor-id-override
      @Override
      public String processorId() {
        return "my-stable-processor-id";
      }

      //#processor-id-override
      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(Persistent.class, received -> {/* ... */}).build();
      }
    }
  };

  static Object o3 = new Object() {
    //#channel-example
    class MyProcessor extends AbstractProcessor {
      private final ActorRef destination;
      private final ActorRef channel;

      public MyProcessor() {
        this.destination = context().actorOf(Props.create(MyDestination.class));
        this.channel = context().actorOf(Channel.props(), "myChannel");
      }

      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(Persistent.class, p -> {
              Persistent out = p.withPayload("done " + p.payload());
              channel.tell(Deliver.create(out, destination.path()), self());
            }).build();
      }
    }

    class MyDestination extends AbstractActor {
      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(ConfirmablePersistent.class, p -> {
              Object payload = p.payload();
              Long sequenceNr = p.sequenceNr();
              int redeliveries = p.redeliveries();
              // ...
              p.confirm();
            }).build();
      }
    }
    //#channel-example

    class MyProcessor2 extends AbstractProcessor {
      private final ActorRef destination;
      private final ActorRef channel;

      public MyProcessor2(ActorRef destination) {
        this.destination = context().actorOf(Props.create(MyDestination.class));
        //#channel-id-override
        this.channel = context().actorOf(Channel.props("my-stable-channel-id"));
        //#channel-id-override

        //#channel-custom-settings
        context().actorOf(
            Channel.props(ChannelSettings.create()
                            .withRedeliverInterval(Duration.create(30, TimeUnit.SECONDS))
                            .withRedeliverMax(15)));
        //#channel-custom-settings

        //#channel-custom-listener
        class MyListener extends AbstractActor {
          @Override public PartialFunction<Object, BoxedUnit> receive() {
            return ReceiveBuilder.
                match(RedeliverFailure.class, r -> {
                  Iterable<ConfirmablePersistent> messages = r.getMessages();
                  // ...
                }).build();
          }
        }

        final ActorRef myListener = context().actorOf(Props.create(MyListener.class));
        context().actorOf(Channel.props(
            ChannelSettings.create().withRedeliverFailureListener(null)));
        //#channel-custom-listener

      }

      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(Persistent.class, p -> {
              Persistent out = p.withPayload("done " + p.payload());
              channel.tell(Deliver.create(out, destination.path()), self());

              //#channel-example-reply
              channel.tell(Deliver.create(out, sender().path()), self());
              //#channel-example-reply
            }).build();
      }
    }
  };

  static Object o4 = new Object() {
    //#save-snapshot
    class MyProcessor extends AbstractProcessor {
      private Object state;

      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(String.class, s -> s.equals("snap"),
                  s -> saveSnapshot(state)).
            match(SaveSnapshotSuccess.class, ss -> {
              SnapshotMetadata metadata = ss.metadata();
              // ...
            }).
            match(SaveSnapshotFailure.class, sf -> {
              SnapshotMetadata metadata = sf.metadata();
              // ...
            }).build();
      }
    }
    //#save-snapshot
  };

  static Object o5 = new Object() {
    //#snapshot-offer
    class MyProcessor extends AbstractProcessor {
      private Object state;

      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(SnapshotOffer.class, s -> {
              state = s.snapshot();
              // ...
            }).
            match(Persistent.class, p -> {/* ...*/}).build();
      }
    }
    //#snapshot-offer

    class MyActor extends AbstractActor {
      ActorRef processor;

      public MyActor() {
        processor = context().actorOf(Props.create(MyProcessor.class));
      }

      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.match(Object.class, o -> {/* ... */}).build();
      }

      private void recover() {
        //#snapshot-criteria
        processor.tell(Recover.create(
            SnapshotSelectionCriteria
                .create(457L, System.currentTimeMillis())), null);
        //#snapshot-criteria
      }
    }
  };

  static Object o6 = new Object() {
    //#batch-write
    class MyProcessor extends AbstractProcessor {
      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(Persistent.class, p -> p.payload().equals("a"),
                  p -> {/* ... */}).
            match(Persistent.class, p -> p.payload().equals("b"),
                  p -> {/* ... */}).build();
      }
    }

    class Example {
      final ActorSystem system    = ActorSystem.create("example");
      final ActorRef    processor = system.actorOf(Props.create(MyProcessor.class));

      public void batchWrite() {
        processor.tell(PersistentBatch
                  .create(asList(Persistent.create("a"),
                                 Persistent.create("b"))), null);
      }

      // ...
    }
    //#batch-write
  };

  static Object o7 = new Object() {
    abstract class MyProcessor extends AbstractProcessor {
      ActorRef destination;

      public void foo() {
        //#persistent-channel-example
        final ActorRef channel = context().actorOf(
            PersistentChannel.props(
                PersistentChannelSettings.create()
                    .withRedeliverInterval(Duration.create(30, TimeUnit.SECONDS))
                    .withRedeliverMax(15)),
            "myPersistentChannel");

        channel.tell(Deliver.create(Persistent.create("example"), destination.path()), self());
        //#persistent-channel-example
        //#persistent-channel-watermarks
        PersistentChannelSettings.create()
              .withPendingConfirmationsMax(10000)
              .withPendingConfirmationsMin(2000);
        //#persistent-channel-watermarks
        //#persistent-channel-reply
        PersistentChannelSettings.create().withReplyPersistent(true);
        //#persistent-channel-reply
      }
    }
  };

  static Object o8 = new Object() {
    //#reliable-event-delivery
    class MyEventsourcedProcessor extends AbstractEventsourcedProcessor {
      private ActorRef destination;
      private ActorRef channel;

      public MyEventsourcedProcessor(ActorRef destination) {
        this.destination = destination;
        this.channel = context().actorOf(Channel.props(), "channel");
      }

      private void handleEvent(String event) {
        // update state
        // ...
        // reliably deliver events
        channel.tell(Deliver.create(
              Persistent.create(event, getCurrentPersistentMessage()),
              destination.path()), self());
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.
            match(String.class, this::handleEvent).build();
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.
            match(String.class, s -> s.equals("cmd"),
                  s -> persist("evt", this::handleEvent)).build();
      }
    }
    //#reliable-event-delivery
  };

  static Object o9 = new Object() {
    //#view
    class MyView extends AbstractView {
      @Override
      public String processorId() {
        return "some-processor-id";
      }

      @Override public PartialFunction<Object, BoxedUnit> receive() {
        return ReceiveBuilder.
            match(Persistent.class, peristent -> {
              // ...
            }).build();
      }
    }
    //#view

    public void usage() {
      final ActorSystem system = ActorSystem.create("example");
      //#view-update
      final ActorRef view = system.actorOf(Props.create(MyView.class));
      view.tell(Update.create(true), null);
      //#view-update
    }
  };
}
