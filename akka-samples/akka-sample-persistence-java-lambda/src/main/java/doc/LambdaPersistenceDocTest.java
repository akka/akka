/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package doc;

import akka.actor.AbstractActor;
import akka.actor.ActorPath;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.*;
import scala.Option;
import scala.PartialFunction;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import java.io.Serializable;

import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;

public class LambdaPersistenceDocTest {

  public interface SomeOtherMessage {}

  public interface ProcessorMethods {
    //#persistence-id
    public String persistenceId();
    //#persistence-id
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
      public MyProcessor() {
        receive(ReceiveBuilder.
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
          match(SomeOtherMessage.class, message -> {
            // message not written to journal
          }).build()
        );
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

        receive(ReceiveBuilder.
          match(Persistent.class, received -> {/* ... */}).build()
        );
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
      //#persistence-id-override
      @Override
      public String persistenceId() {
        return "my-stable-persistence-id";
      }

      //#persistence-id-override
      public MyProcessor4() {
        receive(ReceiveBuilder.
          match(Persistent.class, received -> {/* ... */}).build()
        );
      }
    }

    //#recovery-completed
    class MyPersistentActor5 extends AbstractPersistentActor {

      @Override public String persistenceId() { 
        return "my-stable-persistence-id";
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.
          match(RecoveryCompleted.class, r -> {
            recoveryCompleted();
          }).
          match(String.class, this::handleEvent).build();
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.
          match(String.class, s -> s.equals("cmd"),
            s -> persist("evt", this::handleEvent)).build();
      }      
      
      private void recoveryCompleted() {
          // perform init after recovery, before any other messages
          // ...
      }

      private void handleEvent(String event) {
        // update state
        // ...
      }
      
    }
    //#recovery-completed
  };

  static Object atLeastOnceExample = new Object() {
      //#at-least-once-example
      
      class Msg implements Serializable {
        public final long deliveryId;
        public final String s;
        
        public Msg(long deliveryId, String s) {
          this.deliveryId = deliveryId;
          this.s = s;
        }
      }
      
      class Confirm implements Serializable {
        public final long deliveryId;
        
        public Confirm(long deliveryId) {
          this.deliveryId = deliveryId;
        }
      }
      
  
      class MsgSent implements Serializable {
        public final String s;
        
        public MsgSent(String s) {
          this.s = s;
        }
      }
      class MsgConfirmed implements Serializable {
        public final long deliveryId;
        
        public MsgConfirmed(long deliveryId) {
          this.deliveryId = deliveryId;
        }
      }
      
      class MyPersistentActor extends AbstractPersistentActorWithAtLeastOnceDelivery {
        private final ActorPath destination;

        public MyPersistentActor(ActorPath destination) {
            this.destination = destination;
        }

        @Override
        public PartialFunction<Object, BoxedUnit> receiveCommand() {
          return ReceiveBuilder.
            match(String.class, s -> {
              persist(new MsgSent(s), evt -> updateState(evt));
            }).
            match(Confirm.class, confirm -> {
              persist(new MsgConfirmed(confirm.deliveryId), evt -> updateState(evt));
            }).
            build();
        }

        @Override
        public PartialFunction<Object, BoxedUnit> receiveRecover() {
          return ReceiveBuilder.
              match(Object.class, evt -> updateState(evt)).build();
        }        
        
        void updateState(Object event) {
          if (event instanceof MsgSent) {
            final MsgSent evt = (MsgSent) event;
            deliver(destination, deliveryId -> new Msg(deliveryId, evt.s));
          } else if (event instanceof MsgConfirmed) {
            final MsgConfirmed evt = (MsgConfirmed) event;
            confirmDelivery(evt.deliveryId);
          }
        }
      }

      class MyDestination extends AbstractActor {
        public MyDestination() {
          receive(ReceiveBuilder.
            match(Msg.class, msg -> {
              // ...
              sender().tell(new Confirm(msg.deliveryId), self());
            }).build()
          );
        }
      }
      //#at-least-once-example
    };


  static Object o3 = new Object() {
    //#channel-example
    class MyProcessor extends AbstractProcessor {
      private final ActorRef destination;
      private final ActorRef channel;

      public MyProcessor() {
        this.destination = context().actorOf(Props.create(MyDestination.class));
        this.channel = context().actorOf(Channel.props(), "myChannel");

        receive(ReceiveBuilder.
          match(Persistent.class, p -> {
            Persistent out = p.withPayload("done " + p.payload());
            channel.tell(Deliver.create(out, destination.path()), self());
          }).build()
        );
      }
    }

    class MyDestination extends AbstractActor {
      public MyDestination() {
        receive(ReceiveBuilder.
          match(ConfirmablePersistent.class, p -> {
            Object payload = p.payload();
            Long sequenceNr = p.sequenceNr();
            int redeliveries = p.redeliveries();
            // ...
            p.confirm();
          }).build()
        );
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
          public MyListener() {
            receive(ReceiveBuilder.
              match(RedeliverFailure.class, r -> {
                Iterable<ConfirmablePersistent> messages = r.getMessages();
                // ...
              }).build()
            );
          }
        }

        final ActorRef myListener = context().actorOf(Props.create(MyListener.class));
        context().actorOf(Channel.props(
          ChannelSettings.create().withRedeliverFailureListener(null)));
        //#channel-custom-listener

        receive(ReceiveBuilder.
          match(Persistent.class, p -> {
            Persistent out = p.withPayload("done " + p.payload());
            channel.tell(Deliver.create(out, destination.path()), self());

            //#channel-example-reply
            channel.tell(Deliver.create(out, sender().path()), self());
            //#channel-example-reply
          }).build()
        );
      }
    }
  };

  static Object o4 = new Object() {
    //#save-snapshot
    class MyProcessor extends AbstractProcessor {
      private Object state;

      public MyProcessor() {
        receive(ReceiveBuilder.
          match(String.class, s -> s.equals("snap"),
            s -> saveSnapshot(state)).
          match(SaveSnapshotSuccess.class, ss -> {
            SnapshotMetadata metadata = ss.metadata();
            // ...
          }).
          match(SaveSnapshotFailure.class, sf -> {
            SnapshotMetadata metadata = sf.metadata();
            // ...
          }).build()
        );
      }
    }
    //#save-snapshot
  };

  static Object o5 = new Object() {
    //#snapshot-offer
    class MyProcessor extends AbstractProcessor {
      private Object state;

      public MyProcessor() {
        receive(ReceiveBuilder.
          match(SnapshotOffer.class, s -> {
            state = s.snapshot();
            // ...
          }).
          match(Persistent.class, p -> {/* ...*/}).build()
        );
      }
    }
    //#snapshot-offer

    class MyActor extends AbstractActor {
      ActorRef processor;

      public MyActor() {
        processor = context().actorOf(Props.create(MyProcessor.class));
        receive(ReceiveBuilder.
          match(Object.class, o -> {/* ... */}).build()
        );
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
      public MyProcessor() {
        receive(ReceiveBuilder.
          match(Persistent.class, p -> p.payload().equals("a"),
            p -> {/* ... */}).
          match(Persistent.class, p -> p.payload().equals("b"),
            p -> {/* ... */}).build()
        );
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
    class MyEventsourcedProcessor extends AbstractPersistentActor {
      private ActorRef destination;
      private ActorRef channel;

      public MyEventsourcedProcessor(ActorRef destination) {
        this.destination = destination;
        this.channel = context().actorOf(Channel.props(), "channel");
      }

      @Override public String persistenceId() { 
        return "my-stable-persistence-id";
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
    //#persist-async
    class MyPersistentActor extends AbstractPersistentActor {

      @Override public String persistenceId() { 
        return "my-stable-persistence-id";
      }

      private void handleCommand(String c) {
        sender().tell(c, self());

        persistAsync(String.format("evt-%s-1", c), e -> {
          sender().tell(e, self());
        });
        persistAsync(String.format("evt-%s-2", c), e -> {
          sender().tell(e, self());
        });
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.
          match(String.class, this::handleCommand).build();
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.
          match(String.class, this::handleCommand).build();
      }
    }
    //#persist-async

    public void usage() {
      final ActorSystem system = ActorSystem.create("example");
      //#persist-async-usage
      final ActorRef processor = system.actorOf(Props.create(MyPersistentActor.class));
      processor.tell("a", null);
      processor.tell("b", null);

      // possible order of received messages:
      // a
      // b
      // evt-a-1
      // evt-a-2
      // evt-b-1
      // evt-b-2
      //#persist-async-usage
    }
  };


  static Object o10 = new Object() {
    //#defer
    class MyPersistentActor extends AbstractPersistentActor {

      @Override public String persistenceId() { 
        return "my-stable-persistence-id";
      }

      private void handleCommand(String c) {
        persistAsync(String.format("evt-%s-1", c), e -> {
          sender().tell(e, self());
        });
        persistAsync(String.format("evt-%s-2", c), e -> {
          sender().tell(e, self());
        });

        defer(String.format("evt-%s-3", c), e -> {
          sender().tell(e, self());
        });
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.
          match(String.class, this::handleCommand).build();
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.
          match(String.class, this::handleCommand).build();
      }
    }
    //#defer

    public void usage() {
      final ActorSystem system = ActorSystem.create("example");
      final ActorRef sender = null; // your imaginary sender here
      //#defer-caller
      final ActorRef processor = system.actorOf(Props.create(MyPersistentActor.class));
      processor.tell("a", sender);
      processor.tell("b", sender);

      // order of received messages:
      // a
      // b
      // evt-a-1
      // evt-a-2
      // evt-a-3
      // evt-b-1
      // evt-b-2
      // evt-b-3
      //#defer-caller
    }
  };


  static Object o11 = new Object() {
    //#view
    class MyView extends AbstractPersistentView {
      @Override public String persistenceId() { return "some-persistence-id"; }
      @Override public String viewId() { return "some-persistence-id-view"; }

      public MyView() {
        receive(ReceiveBuilder.
          match(Object.class, p -> isPersistent(),  persistent -> {
            // ...
          }).build()
        );
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
