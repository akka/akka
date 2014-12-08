/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence;

import akka.actor.ActorPath;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.japi.Function;
import akka.japi.Procedure;
import akka.persistence.*;
import scala.Option;
import scala.concurrent.duration.Duration;
import java.io.Serializable;

import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;

public class PersistenceDocTest {

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
        class MyActor extends UntypedActor {
            ActorRef persistentActor;

            public void onReceive(Object message) throws Exception {
            }

            private void recover() {
                //#recover-explicit
                persistentActor.tell(Recover.create(), getSelf());
                //#recover-explicit
            }
        }
    };

    static Object o2 = new Object() {
        abstract class MyPersistentActor1 extends UntypedPersistentActor {
            //#recover-on-start-disabled
            @Override
            public void preStart() {}
            //#recover-on-start-disabled

            //#recover-on-restart-disabled
            @Override
            public void preRestart(Throwable reason, Option<Object> message) {}
            //#recover-on-restart-disabled
        }

        abstract class MyPersistentActor2 extends UntypedPersistentActor {
            //#recover-on-start-custom
            @Override
            public void preStart() {
                getSelf().tell(Recover.create(457L), getSelf());
            }
            //#recover-on-start-custom
        }

        class MyPersistentActor4 extends UntypedPersistentActor implements PersistentActorMethods {
            //#persistence-id-override
            @Override
            public String persistenceId() {
                return "my-stable-persistence-id";
            }
            //#persistence-id-override
            @Override
            public void onReceiveRecover(Object message) throws Exception {}
            @Override
            public void onReceiveCommand(Object message) throws Exception {}
        }
        
        class MyPersistentActor5 extends UntypedPersistentActor {
          @Override
          public String persistenceId() {
            return "persistence-id";
          }
    
          //#recovery-completed
          @Override
          public void onReceiveRecover(Object message) {
            if (message instanceof RecoveryCompleted) {
              // perform init after recovery, before any other messages
            }
          }
    
          @Override
          public void onReceiveCommand(Object message) throws Exception {
            if (message instanceof String) {
              // ...
            } else {
              unhandled(message);
            }
          }
          //#recovery-completed
        }
    };

    static Object fullyDisabledRecoveryExample = new Object() {
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
      
      class MyPersistentActor extends UntypedPersistentActorWithAtLeastOnceDelivery {
        private final ActorPath destination;
        
        @Override
        public String persistenceId() { return "persistence-id"; }

        public MyPersistentActor(ActorPath destination) {
            this.destination = destination;
        }

        @Override
        public void onReceiveCommand(Object message) {
          if (message instanceof String) {
            String s = (String) message;
            persist(new MsgSent(s), new Procedure<MsgSent>() {
              public void apply(MsgSent evt) {
                updateState(evt);
              }
            });
          } else if (message instanceof Confirm) {
            Confirm confirm = (Confirm) message;
            persist(new MsgConfirmed(confirm.deliveryId), new Procedure<MsgConfirmed>() {
              public void apply(MsgConfirmed evt) {
                updateState(evt);
              }
            });
          } else {
            unhandled(message);
          }
        }
        
        @Override
        public void onReceiveRecover(Object event) {
          updateState(event);
        }
        
        void updateState(Object event) {
          if (event instanceof MsgSent) {
            final MsgSent evt = (MsgSent) event;
            deliver(destination, new Function<Long, Object>() {
              public Object apply(Long deliveryId) {
                return new Msg(deliveryId, evt.s);
              }
            });
          } else if (event instanceof MsgConfirmed) {
            final MsgConfirmed evt = (MsgConfirmed) event;
            confirmDelivery(evt.deliveryId);
          }
        }
      }

      class MyDestination extends UntypedActor {
        public void onReceive(Object message) throws Exception {
          if (message instanceof Msg) {
            Msg msg = (Msg) message;
            // ...
            getSender().tell(new Confirm(msg.deliveryId), getSelf());
          } else {
            unhandled(message);
          }
        }
      }
      //#at-least-once-example
    };

    static Object o4 = new Object() {
        class MyPersistentActor extends UntypedPersistentActor {
            @Override
            public String persistenceId() { return "persistence-id"; }
            
            //#save-snapshot
            private Object state;

            @Override
            public void onReceiveCommand(Object message) {
                if (message.equals("snap")) {
                    saveSnapshot(state);
                } else if (message instanceof SaveSnapshotSuccess) {
                    SnapshotMetadata metadata = ((SaveSnapshotSuccess)message).metadata();
                    // ...
                } else if (message instanceof SaveSnapshotFailure) {
                    SnapshotMetadata metadata = ((SaveSnapshotFailure)message).metadata();
                    // ...
                }
            }
            //#save-snapshot
            
            @Override
            public void onReceiveRecover(Object event) {
            }
        }
        
    };

    static Object o5 = new Object() {
        class MyPersistentActor extends UntypedPersistentActor {
            @Override
            public String persistenceId() { return "persistence-id"; }
            
            //#snapshot-offer
            private Object state;

            @Override
            public void onReceiveRecover(Object message) {
                if (message instanceof SnapshotOffer) {
                    state = ((SnapshotOffer)message).snapshot();
                    // ...
                } else if (message instanceof RecoveryCompleted) {
                    // ...
                } else {
                    // ...
                }
            }
            //#snapshot-offer
            
            @Override
            public void onReceiveCommand(Object message) {
            }
        }

        class MyActor extends UntypedActor {
            ActorRef persistentActor;

            public MyActor() {
                persistentActor = getContext().actorOf(Props.create(MyPersistentActor.class));
            }

            public void onReceive(Object message) throws Exception {
                // ...
            }

            private void recover() {
                //#snapshot-criteria
                persistentActor.tell(Recover.create(SnapshotSelectionCriteria.create(457L, 
                    System.currentTimeMillis())), null);
                //#snapshot-criteria
            }
        }
    };

    static Object o9 = new Object() {
        //#persist-async
        class MyPersistentActor extends UntypedPersistentActor {
            @Override
            public String persistenceId() { return "some-persistence-id"; }

            @Override
            public void onReceiveRecover(Object msg) {
                // handle recovery here
            }

            @Override
            public void onReceiveCommand(Object msg) {
                sender().tell(msg, getSelf());

                persistAsync(String.format("evt-%s-1", msg), new Procedure<String>(){
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                    }
                });
                persistAsync(String.format("evt-%s-2", msg), new Procedure<String>(){
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                    }
                });
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
        class MyPersistentActor extends UntypedPersistentActor {
            @Override
            public String persistenceId() { return "some-persistence-id"; }

            @Override
            public void onReceiveRecover(Object msg) {
                // handle recovery here
            }

            @Override
            public void onReceiveCommand(Object msg) {
                final Procedure<String> replyToSender = new Procedure<String>() {
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, getSelf());
                    }
                };

                persistAsync(String.format("evt-%s-1", msg), replyToSender);
                persistAsync(String.format("evt-%s-2", msg), replyToSender);
                defer(String.format("evt-%s-3", msg), replyToSender);
            }
        }
        //#defer

        public void usage() {
            final ActorSystem system = ActorSystem.create("example");
            //#defer-caller
            final ActorRef persistentActor = system.actorOf(Props.create(MyPersistentActor.class));
            persistentActor.tell("a", null);
            persistentActor.tell("b", null);

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
        class MyView extends UntypedPersistentView {
            @Override
            public String persistenceId() { return "some-persistence-id"; }
            
            @Override
            public String viewId() { return "my-stable-persistence-view-id"; }

            @Override
            public void onReceive(Object message) throws Exception {
                if (isPersistent()) {
                    // handle message from Journal...
                } else if (message instanceof String) {
                    // handle message from user...
                } else {
                  unhandled(message);
                }
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
