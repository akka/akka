/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence;

import java.util.concurrent.TimeUnit;

import scala.Option;
import scala.concurrent.duration.Duration;

import akka.actor.*;
import akka.japi.Procedure;
import akka.persistence.*;

import static java.util.Arrays.asList;

public class PersistenceDocTest {

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
        class MyProcessor extends UntypedProcessor {
            public void onReceive(Object message) throws Exception {
                if (message instanceof Persistent) {
                    // message successfully written to journal
                    Persistent persistent = (Persistent)message;
                    Object payload = persistent.payload();
                    Long sequenceNr = persistent.sequenceNr();
                    // ...
                } else if (message instanceof PersistenceFailure) {
                    // message failed to be written to journal
                    PersistenceFailure failure = (PersistenceFailure)message;
                    Object payload = failure.payload();
                    Long sequenceNr = failure.sequenceNr();
                    Throwable cause = failure.cause();
                    // ...
                } else {
                    // message not written to journal
                }
            }
        }
        //#definition

        class MyActor extends UntypedActor {
            ActorRef processor;

            public MyActor() {
                //#usage
                processor = getContext().actorOf(Props.create(MyProcessor.class), "myProcessor");

                processor.tell(Persistent.create("foo"), null);
                processor.tell("bar", null);
                //#usage
            }

            public void onReceive(Object message) throws Exception {
                // ...
            }

            private void recover() {
                //#recover-explicit
                processor.tell(Recover.create(), null);
                //#recover-explicit
            }
        }
    };

    static Object o2 = new Object() {
        abstract class MyProcessor1 extends UntypedProcessor {
            //#recover-on-start-disabled
            @Override
            public void preStart() {}
            //#recover-on-start-disabled

            //#recover-on-restart-disabled
            @Override
            public void preRestart(Throwable reason, Option<Object> message) {}
            //#recover-on-restart-disabled
        }

        abstract class MyProcessor2 extends UntypedProcessor {
            //#recover-on-start-custom
            @Override
            public void preStart() {
                getSelf().tell(Recover.create(457L), null);
            }
            //#recover-on-start-custom
        }

        abstract class MyProcessor3 extends UntypedProcessor {
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

        class MyProcessor4 extends UntypedProcessor implements ProcessorMethods {
            //#processor-id-override
            @Override
            public String processorId() {
                return "my-stable-processor-id";
            }
            //#processor-id-override
            @Override
            public void onReceive(Object message) throws Exception {}
        }
    };

    static Object o3 = new Object() {
        //#channel-example
        class MyProcessor extends UntypedProcessor {
            private final ActorRef destination;
            private final ActorRef channel;

            public MyProcessor() {
                this.destination = getContext().actorOf(Props.create(MyDestination.class));
                this.channel = getContext().actorOf(Channel.props(), "myChannel");
            }

            public void onReceive(Object message) throws Exception {
                if (message instanceof Persistent) {
                    Persistent p = (Persistent)message;
                    Persistent out = p.withPayload("done " + p.payload());
                    channel.tell(Deliver.create(out, destination.path()), getSelf());
                }
            }
        }

        class MyDestination extends UntypedActor {
            public void onReceive(Object message) throws Exception {
                if (message instanceof ConfirmablePersistent) {
                    ConfirmablePersistent p = (ConfirmablePersistent)message;
                    Object payload = p.payload();
                    Long sequenceNr = p.sequenceNr();
                    int redeliveries = p.redeliveries();
                    // ...
                    p.confirm();
                }
            }
        }
        //#channel-example

        class MyProcessor2 extends UntypedProcessor {
            private final ActorRef destination;
            private final ActorRef channel;

            public MyProcessor2(ActorRef destination) {
                this.destination = getContext().actorOf(Props.create(MyDestination.class));
                //#channel-id-override
                this.channel = getContext().actorOf(Channel.props("my-stable-channel-id"));
                //#channel-id-override

                //#channel-custom-settings
                getContext().actorOf(Channel.props(
                        ChannelSettings.create()
                        .withRedeliverInterval(Duration.create(30, TimeUnit.SECONDS))
                        .withRedeliverMax(15)));
                //#channel-custom-settings

                //#channel-custom-listener
                class MyListener extends UntypedActor {
                    @Override
                    public void onReceive(Object message) throws Exception {
                        if (message instanceof RedeliverFailure) {
                            Iterable<ConfirmablePersistent> messages =
                                    ((RedeliverFailure)message).getMessages();
                            // ...
                        }
                    }
                }

                final ActorRef myListener = getContext().actorOf(Props.create(MyListener.class));
                getContext().actorOf(Channel.props(
                        ChannelSettings.create().withRedeliverFailureListener(null)));
                //#channel-custom-listener

            }

            public void onReceive(Object message) throws Exception {
                if (message instanceof Persistent) {
                    Persistent p = (Persistent)message;
                    Persistent out = p.withPayload("done " + p.payload());
                    channel.tell(Deliver.create(out, destination.path()), getSelf());

                    //#channel-example-reply
                    channel.tell(Deliver.create(out, getSender().path()), getSelf());
                    //#channel-example-reply
                }
            }
        }
    };

    static Object o4 = new Object() {
        //#save-snapshot
        class MyProcessor extends UntypedProcessor {
            private Object state;

            @Override
            public void onReceive(Object message) throws Exception {
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
        }
        //#save-snapshot
    };

    static Object o5 = new Object() {
        //#snapshot-offer
        class MyProcessor extends UntypedProcessor {
            private Object state;

            @Override
            public void onReceive(Object message) throws Exception {
                if (message instanceof SnapshotOffer) {
                    state = ((SnapshotOffer)message).snapshot();
                    // ...
                } else if (message instanceof Persistent) {
                    // ...
                }
            }
        }
        //#snapshot-offer

        class MyActor extends UntypedActor {
            ActorRef processor;

            public MyActor() {
                processor = getContext().actorOf(Props.create(MyProcessor.class));
            }

            public void onReceive(Object message) throws Exception {
                // ...
            }

            private void recover() {
                //#snapshot-criteria
                processor.tell(Recover.create(SnapshotSelectionCriteria.create(457L, System.currentTimeMillis())), null);
                //#snapshot-criteria
            }
        }
    };

    static Object o6 = new Object() {
        //#batch-write
        class MyProcessor extends UntypedProcessor {
            public void onReceive(Object message) throws Exception {
                if (message instanceof Persistent) {
                    Persistent p = (Persistent)message;
                    if (p.payload().equals("a")) { /* ... */ }
                    if (p.payload().equals("b")) { /* ... */ }
                }
            }
        }

        class Example {
            final ActorSystem system = ActorSystem.create("example");
            final ActorRef processor = system.actorOf(Props.create(MyProcessor.class));

            public void batchWrite() {
                processor.tell(PersistentBatch.create(asList(
                        Persistent.create("a"),
                        Persistent.create("b"))), null);
            }

            // ...
        }
        //#batch-write
    };

    static Object o7 = new Object() {
        abstract class MyProcessor extends UntypedProcessor {
            ActorRef destination;

            public void foo() {
                //#persistent-channel-example
                final ActorRef channel = getContext().actorOf(PersistentChannel.props(
                        PersistentChannelSettings.create()
                        .withRedeliverInterval(Duration.create(30, TimeUnit.SECONDS))
                        .withRedeliverMax(15)), "myPersistentChannel");

                channel.tell(Deliver.create(Persistent.create("example"), destination.path()), getSelf());
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
        class MyEventsourcedProcessor extends UntypedEventsourcedProcessor {
            private ActorRef destination;
            private ActorRef channel;

            public MyEventsourcedProcessor(ActorRef destination) {
                this.destination = destination;
                this.channel = getContext().actorOf(Channel.props(), "channel");
            }

            private void handleEvent(String event) {
                // update state
                // ...
                // reliably deliver events
                channel.tell(Deliver.create(Persistent.create(
                        event, getCurrentPersistentMessage()), destination.path()), getSelf());
            }

            public void onReceiveRecover(Object msg) {
                if (msg instanceof String) {
                    handleEvent((String)msg);
                }
            }

            public void onReceiveCommand(Object msg) {
                if (msg.equals("cmd")) {
                    persist("evt", new Procedure<String>() {
                        public void apply(String event) {
                            handleEvent(event);
                        }
                    });
                }
            }
        }
        //#reliable-event-delivery
    };

    static Object o9 = new Object() {
        //#view
        class MyView extends UntypedView {
            @Override
            public String processorId() {
                return "some-processor-id";
            }

            @Override
            public void onReceive(Object message) throws Exception {
                if (message instanceof Persistent) {
                    // ...
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
