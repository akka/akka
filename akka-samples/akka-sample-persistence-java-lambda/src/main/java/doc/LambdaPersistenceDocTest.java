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

  public interface PersistentActorMethods {
    //#persistence-id
    public String persistenceId();
    //#persistence-id
    //#recovery-status
    public boolean recoveryRunning();
    public boolean recoveryFinished();
    //#recovery-status
  }

  static Object o1 = new Object() {

    private void recover() {
      ActorRef persistentActor =null;

      //#recover-explicit
      persistentActor.tell(Recover.create(), null);
      //#recover-explicit
    }
    
  };

  static Object o2 = new Object() {
    abstract class MyPersistentActor1 extends AbstractPersistentActor {
      //#recover-on-start-disabled
      @Override
      public void preStart() {}
      //#recover-on-start-disabled

      //#recover-on-restart-disabled
      @Override
      public void preRestart(Throwable reason, Option<Object> message) {}
      //#recover-on-restart-disabled
    }

    abstract class MyPersistentActor2 extends AbstractPersistentActor {
      //#recover-on-start-custom
      @Override
      public void preStart() {
        self().tell(Recover.create(457L), null);
      }
      //#recover-on-start-custom
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
  };

  static Object fullyDisabledRecoveyExample = new Object() {
      abstract class MyPersistentActor1 extends UntypedPersistentActor {
          //#recover-fully-disabled
          @Override
          public void preStart() { getSelf().tell(Recover.create(0L), getSelf()); }
          //#recover-fully-disabled
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
        private final ActorPath destination;

        public MyPersistentActor(ActorPath destination) {
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

      private void recover() {
        //#snapshot-criteria
        persistentActor.tell(Recover.create(
          SnapshotSelectionCriteria
            .create(457L, System.currentTimeMillis())), null);
        //#snapshot-criteria
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
