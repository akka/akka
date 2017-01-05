/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.persistence;

import akka.actor.*;
import akka.japi.Procedure;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.BackoffSupervisor;
import akka.persistence.*;
import akka.persistence.journal.EventAdapter;
import akka.persistence.journal.EventSeq;
import scala.Option;
import scala.concurrent.duration.Duration;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public class LambdaPersistenceDocTest {

  public interface SomeOtherMessage {}

  public interface PersistentActorMethods {
    //#persistence-id
    public String persistenceId();
    //#persistence-id
    //#recovery-status
    public boolean recoveryRunning();
    public boolean recoveryFinished();
    //#recovery-status
  }

  static Object o2 = new Object() {
    abstract class MyPersistentActor1 extends AbstractPersistentActor {
      //#recovery-disabled
      @Override
      public Recovery recovery() {
        return Recovery.none();
      }
      //#recovery-disabled

      //#recover-on-restart-disabled
      @Override
      public void preRestart(Throwable reason, Option<Object> message) {}
      //#recover-on-restart-disabled
    }

    abstract class MyPersistentActor2 extends AbstractPersistentActor {
      //#recovery-custom
      @Override
      public Recovery recovery() {
        return Recovery.create(457L);
      }
      //#recovery-custom
    }

    class MyPersistentActor4 extends AbstractPersistentActor implements PersistentActorMethods {
      //#persistence-id-override
      @Override
      public String persistenceId() {
        return "my-stable-persistence-id";
      }

      //#persistence-id-override

      @Override
      public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.
          match(String.class, cmd -> {/* ... */}).build();
      }

      @Override
      public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.
            match(String.class, evt -> {/* ... */}).build();
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
            // perform init after recovery, before any other messages
            // ...
          }).
          match(String.class, this::handleEvent).build();
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.
          match(String.class, s -> s.equals("cmd"),
            s -> persist("evt", this::handleEvent)).build();
      }

      private void handleEvent(String event) {
        // update state
        // ...
      }

    }
    //#recovery-completed

    abstract class MyPersistentActor6 extends AbstractPersistentActor {
      //#recovery-no-snap
      @Override
      public Recovery recovery() {
        return Recovery.create(SnapshotSelectionCriteria.none());
      }
      //#recovery-no-snap
    }

    abstract class MyActor extends AbstractPersistentActor {
      //#backoff
      @Override
      public void preStart() throws Exception {
        final Props childProps = Props.create(MyPersistentActor1.class);
        final Props props = BackoffSupervisor.props(
          childProps,
          "myActor",
          Duration.create(3, TimeUnit.SECONDS),
          Duration.create(30, TimeUnit.SECONDS),
          0.2);
        context().actorOf(props, "mySupervisor");
        super.preStart();
      }
      //#backoff
    }
  };

  static Object atLeastOnceExample = new Object() {
      //#at-least-once-example

      class Msg implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long deliveryId;
        public final String s;

        public Msg(long deliveryId, String s) {
          this.deliveryId = deliveryId;
          this.s = s;
        }
      }

      class Confirm implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long deliveryId;

        public Confirm(long deliveryId) {
          this.deliveryId = deliveryId;
        }
      }


      class MsgSent implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String s;

        public MsgSent(String s) {
          this.s = s;
        }
      }
      class MsgConfirmed implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long deliveryId;

        public MsgConfirmed(long deliveryId) {
          this.deliveryId = deliveryId;
        }
      }

      class MyPersistentActor extends AbstractPersistentActorWithAtLeastOnceDelivery {
        private final ActorSelection destination;

        public MyPersistentActor(ActorSelection destination) {
            this.destination = destination;
        }

        @Override public String persistenceId() {
          return "persistence-id";
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

  static Object o4 = new Object() {
    class MyPersistentActor extends AbstractPersistentActor {

      //#save-snapshot
      private Object state;

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
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
      //#save-snapshot

      @Override public String persistenceId() {
        return "persistence-id";
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.
          match(RecoveryCompleted.class, r -> {/* ...*/}).build();
      }

    }
  };

  static Object o5 = new Object() {

    class MyPersistentActor extends AbstractPersistentActor {

      //#snapshot-criteria
      @Override
      public Recovery recovery() {
        return Recovery.create(
          SnapshotSelectionCriteria
            .create(457L, System.currentTimeMillis()));
      }
      //#snapshot-criteria

      //#snapshot-offer
      private Object state;

      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.
          match(SnapshotOffer.class, s -> {
            state = s.snapshot();
            // ...
          }).
          match(String.class, s -> {/* ...*/}).build();
      }
      //#snapshot-offer

      @Override public String persistenceId() {
        return "persistence-id";
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.
          match(String.class, s -> {/* ...*/}).build();
      }
    }


    class MyActor extends AbstractActor {
      ActorRef persistentActor;

      public MyActor() {
        persistentActor = context().actorOf(Props.create(MyPersistentActor.class));
        receive(ReceiveBuilder.
          match(Object.class, o -> {/* ... */}).build()
        );
      }
    }
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
      final ActorRef persistentActor = system.actorOf(Props.create(MyPersistentActor.class));
      persistentActor.tell("a", null);
      persistentActor.tell("b", null);

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

        deferAsync(String.format("evt-%s-3", c), e -> {
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
      final ActorRef persistentActor = system.actorOf(Props.create(MyPersistentActor.class));
      persistentActor.tell("a", sender);
      persistentActor.tell("b", sender);

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

    class MyPersistentActor extends AbstractPersistentActor {
      @Override
      public String persistenceId() {
        return "my-stable-persistence-id";
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.matchAny(event -> {}).build();
      }

      //#nested-persist-persist
      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
        final Procedure<String> replyToSender = event -> sender().tell(event, self());

        return ReceiveBuilder
          .match(String.class, msg -> {
            persist(String.format("%s-outer-1", msg), event -> {
              sender().tell(event, self());
              persist(String.format("%s-inner-1", event), replyToSender);
            });

            persist(String.format("%s-outer-2", msg), event -> {
              sender().tell(event, self());
              persist(String.format("%s-inner-2", event), replyToSender);
            });
          })
          .build();
      }
      //#nested-persist-persist

      void usage(ActorRef persistentActor) {
        //#nested-persist-persist-caller
        persistentActor.tell("a", ActorRef.noSender());
        persistentActor.tell("b", ActorRef.noSender());

        // order of received messages:
        // a
        // a-outer-1
        // a-outer-2
        // a-inner-1
        // a-inner-2
        // and only then process "b"
        // b
        // b-outer-1
        // b-outer-2
        // b-inner-1
        // b-inner-2

        //#nested-persist-persist-caller
      }
    }


    class MyPersistAsyncActor extends AbstractPersistentActor {
      @Override
      public String persistenceId() {
        return "my-stable-persistence-id";
      }

      @Override public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder.matchAny(event -> {}).build();
      }

      //#nested-persistAsync-persistAsync
      @Override public PartialFunction<Object, BoxedUnit> receiveRecover() {
          final Procedure<String> replyToSender = event -> sender().tell(event, self());

        return ReceiveBuilder
          .match(String.class, msg -> {
            persistAsync(String.format("%s-outer-1", msg ), event -> {
              sender().tell(event, self());
              persistAsync(String.format("%s-inner-1", event), replyToSender);
            });

            persistAsync(String.format("%s-outer-2", msg ), event -> {
              sender().tell(event, self());
              persistAsync(String.format("%s-inner-1", event), replyToSender);
            });
          })
          .build();
      }
      //#nested-persistAsync-persistAsync

      void usage(ActorRef persistentActor) {
        //#nested-persistAsync-persistAsync-caller
        persistentActor.tell("a", self());
        persistentActor.tell("b", self());

        // order of received messages:
        // a
        // b
        // a-outer-1
        // a-outer-2
        // b-outer-1
        // b-outer-2
        // a-inner-1
        // a-inner-2
        // b-inner-1
        // b-inner-2

        // which can be seen as the following causal relationship:
        // a -> a-outer-1 -> a-outer-2 -> a-inner-1 -> a-inner-2
        // b -> b-outer-1 -> b-outer-2 -> b-inner-1 -> b-inner-2

        //#nested-persistAsync-persistAsync-caller
      }
    }
  };

  static Object o14 = new Object() {
    //#safe-shutdown
    final class Shutdown {
    }

    class MyPersistentActor extends AbstractPersistentActor {
      @Override
      public String persistenceId() {
        return "some-persistence-id";
      }

      @Override
      public PartialFunction<Object, BoxedUnit> receiveCommand() {
        return ReceiveBuilder
          .match(Shutdown.class, shutdown -> {
            context().stop(self());
          })
          .match(String.class, msg -> {
            System.out.println(msg);
            persist("handle-" + msg, e -> System.out.println(e));
          })
          .build();
      }

      @Override
      public PartialFunction<Object, BoxedUnit> receiveRecover() {
        return ReceiveBuilder.matchAny(any -> {}).build();
      }

    }
    //#safe-shutdown


    public void usage() {
      final ActorSystem system = ActorSystem.create("example");
      final ActorRef persistentActor = system.actorOf(Props.create(MyPersistentActor.class));
      //#safe-shutdown-example-bad
      // UN-SAFE, due to PersistentActor's command stashing:
      persistentActor.tell("a", ActorRef.noSender());
      persistentActor.tell("b", ActorRef.noSender());
      persistentActor.tell(PoisonPill.getInstance(), ActorRef.noSender());
      // order of received messages:
      // a
      //   # b arrives at mailbox, stashing;        internal-stash = [b]
      //   # PoisonPill arrives at mailbox, stashing; internal-stash = [b, Shutdown]
      // PoisonPill is an AutoReceivedMessage, is handled automatically
      // !! stop !!
      // Actor is stopped without handling `b` nor the `a` handler!
      //#safe-shutdown-example-bad

      //#safe-shutdown-example-good
      // SAFE:
      persistentActor.tell("a", ActorRef.noSender());
      persistentActor.tell("b", ActorRef.noSender());
      persistentActor.tell(new Shutdown(), ActorRef.noSender());
      // order of received messages:
      // a
      //   # b arrives at mailbox, stashing;        internal-stash = [b]
      //   # Shutdown arrives at mailbox, stashing; internal-stash = [b, Shutdown]
      // handle-a
      //   # unstashing;                            internal-stash = [Shutdown]
      // b
      // handle-b
      //   # unstashing;                            internal-stash = []
      // Shutdown
      // -- stop --
      //#safe-shutdown-example-good
    }
  };
}
