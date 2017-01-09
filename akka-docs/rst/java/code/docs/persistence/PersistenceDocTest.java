/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.persistence;

import java.util.concurrent.TimeUnit;

import akka.actor.*;
import akka.pattern.BackoffSupervisor;
import scala.concurrent.duration.Duration;
import akka.japi.Function;
import akka.japi.Procedure;
import akka.persistence.*;

import java.io.Serializable;

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

    static Object o2 = new Object() {
        abstract class MyPersistentActor1 extends UntypedPersistentActor {
            //#recovery-disabled
            @Override
            public Recovery recovery() {
                return Recovery.none();
            }
            //#recovery-disabled
        }

        abstract class MyPersistentActor2 extends UntypedPersistentActor {
            //#recovery-custom
            @Override
            public Recovery recovery() {
                return Recovery.create(457L);
            }
            //#recovery-custom
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

        abstract class MyPersistentActor6 extends UntypedPersistentActor {
            //#recovery-no-snap
            @Override
            public Recovery recovery() {
                return Recovery.create(SnapshotSelectionCriteria.none());
            }
            //#recovery-no-snap
        }
        
        abstract class MyActor extends UntypedPersistentActor {
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
            getContext().actorOf(props, "mySupervisor");
            super.preStart();
          }
          //#backoff
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
        private final ActorSelection destination;
        
        @Override
        public String persistenceId() { return "persistence-id"; }

        public MyPersistentActor(ActorSelection destination) {
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

            //#snapshot-criteria
            @Override
            public Recovery recovery() {
                return Recovery.create(
                SnapshotSelectionCriteria
                    .create(457L, System.currentTimeMillis()));
            }
            //#snapshot-criteria

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
                sender().tell(msg, self());

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
                        sender().tell(event, self());
                    }
                };

                persistAsync(String.format("evt-%s-1", msg), replyToSender);
                persistAsync(String.format("evt-%s-2", msg), replyToSender);
                deferAsync(String.format("evt-%s-3", msg), replyToSender);
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

        class MyPersistentActor extends UntypedPersistentActor {
            @Override
            public String persistenceId() {
                return "my-stable-persistence-id";
            }

            @Override
            public void onReceiveRecover(Object msg) {
                // handle recovery here
            }

            //#nested-persist-persist
            @Override
            public void onReceiveCommand(Object msg) {
                final Procedure<String> replyToSender = new Procedure<String>() {
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                    }
                };

                final Procedure<String> outer1Callback = new Procedure<String>() {
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                        persist(String.format("%s-inner-1", msg), replyToSender);
                    }
                };
                final Procedure<String> outer2Callback = new Procedure<String>() {
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                        persist(String.format("%s-inner-2", msg), replyToSender);
                    }
                };

                persist(String.format("%s-outer-1", msg), outer1Callback);
                persist(String.format("%s-outer-2", msg), outer2Callback);
            }
            //#nested-persist-persist

            void usage(ActorRef persistentActor) {
                //#nested-persist-persist-caller
                persistentActor.tell("a", self());
                persistentActor.tell("b", self());

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


        class MyPersistAsyncActor extends UntypedPersistentActor {
            @Override
            public String persistenceId() {
                return "my-stable-persistence-id";
            }

            @Override
            public void onReceiveRecover(Object msg) {
                // handle recovery here
            }

        //#nested-persistAsync-persistAsync
            @Override
            public void onReceiveCommand(Object msg) {
                final Procedure<String> replyToSender = new Procedure<String>() {
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                    }
                };

                final Procedure<String> outer1Callback = new Procedure<String>() {
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                        persistAsync(String.format("%s-inner-1", msg), replyToSender);
                    }
                };
                final Procedure<String> outer2Callback = new Procedure<String>() {
                    @Override
                    public void apply(String event) throws Exception {
                        sender().tell(event, self());
                        persistAsync(String.format("%s-inner-1", msg), replyToSender);
                    }
                };

                persistAsync(String.format("%s-outer-1", msg), outer1Callback);
                persistAsync(String.format("%s-outer-2", msg), outer2Callback);
            }
            //#nested-persistAsync-persistAsync


            void usage(ActorRef persistentActor) {
                //#nested-persistAsync-persistAsync-caller
                persistentActor.tell("a", ActorRef.noSender());
                persistentActor.tell("b", ActorRef.noSender());

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

    static Object o13 = new Object() {
        //#safe-shutdown
        final class Shutdown {}

        class MyPersistentActor extends UntypedPersistentActor {
            @Override
            public String persistenceId() {
                return "some-persistence-id";
            }

            @Override
            public void onReceiveCommand(Object msg) throws Exception {
                if (msg instanceof Shutdown) {
                    context().stop(self());
                } else if (msg instanceof String) {
                    System.out.println(msg);
                    persist("handle-" + msg, new Procedure<String>() {
                        @Override
                        public void apply(String param) throws Exception {
                            System.out.println(param);
                        }
                    });
                } else unhandled(msg);
            }

            @Override
            public void onReceiveRecover(Object msg) throws Exception {
                // handle recovery...
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
