/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test;

import akka.actor.*;
import akka.amqp.*;
import akka.dispatch.*;

import static akka.japi.Util.manifest;

import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Duration;
import akka.util.Timeout;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import scala.reflect.ClassManifest;
import scala.Tuple2;

import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"unchecked"})
public class ExampleSessionJava {

    ActorSystem system = ActorSystem.create("ExampleSessionJava", ConfigFactory.load().getConfig("example"));
    LoggingAdapter log = Logging.getLogger(system, this);
    SettingsImpl settings = new SettingsImpl(system.settings().config());
    Timeout timeout = new Timeout(settings.Timeout());
    static Charset utf8Charset = Charset.forName("UTF-8");
    ActorRef amqp = system.actorOf(new Props(AMQPActor.class));
    // defaults to amqp://guest:guest@localhost:5672/
    Future<ActorRef> connection = Patterns.ask(amqp, new ConnectionRequest(new ConnectionParameters()), timeout).
            mapTo(manifest(ActorRef.class));

    Props props = new Props(DirectDeliveryHandlerActor.class);
    ActorRef deliveryHandler = system.actorOf(props, "deliveryHandlerActor");

    public static void main(String... args) {
        new ExampleSessionJava();
    }

    public ExampleSessionJava() {

        try {

            printTopic("DIRECT");
            direct();

            printTopic("FANOUT");
            fanout();

            printTopic("TOPIC");
            topic();

            printTopic("CALLBACK");
            callback();

            // postStop everything the amqp tree except the main AMQP supervisor
            // all connections/consumers/producers will be stopped
        } catch (Exception e) {
            e.printStackTrace();
        } finally {

            system.scheduler().scheduleOnce(Duration.create(10000, TimeUnit.MILLISECONDS), new Runnable() {
                @Override
                public void run() {
                    log.debug("***** shutting down");
                    system.shutdown();
                }
            });

            printTopic("Happy hAkking :-)");
        }
    }

    private void printTopic(String topic) {

        log.info("");
        log.info("==== " + topic + " ===");
        log.info("");
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException ignore) {
        }
    }


    private void direct() throws InterruptedException {

        final ExchangeParameters exchangeParameters = new ExchangeParameters("my_direct_exchange", Direct.getInstance());
        final ConsumerParameters consumerParameters = new ConsumerParameters("some.routing", deliveryHandler, exchangeParameters);
        final ProducerParameters producerParameters = new ProducerParameters(exchangeParameters);

        Future<ActorRef> consumer = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ConsumerRequest(consumerParameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        final Future<ActorRef> producer = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ProducerRequest(producerParameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        consumer.foreach(new Foreach<ActorRef>() {
            public void each(ActorRef c) {

                producer.foreach(new Foreach<ActorRef>() {
                    public void each(ActorRef p) {
                        p.tell(new Message("@jonas_boner: You sucked!!".getBytes(utf8Charset), "some.routing"));
                    }
                });
            }
        });
    }


    private void fanout() throws InterruptedException {
        
        final ExchangeParameters exchangeParameters = new ExchangeParameters("my_fanout_exchange", Fanout.getInstance());

        ActorRef bushDeliveryHandler = system.actorOf(props);

        Props obamaProps = new Props(DirectObamaDeliveryHandlerActor.class);
        ActorRef obamaDeliveryHandler = system.actorOf(obamaProps);

        final ConsumerParameters consumer1Parameters = new ConsumerParameters("@george_bush", bushDeliveryHandler, exchangeParameters);
        final ConsumerParameters consumer2Parameters = new ConsumerParameters("@barack_obama", obamaDeliveryHandler, exchangeParameters);
        final ProducerParameters producerParameters = new ProducerParameters(exchangeParameters);
        
        Future<ActorRef> consumer1 = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ConsumerRequest(consumer1Parameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        Future<ActorRef> consumer2 = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ConsumerRequest(consumer2Parameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        Future<Tuple2<ActorRef,ActorRef>> consumers = consumer1.zip(consumer2);


        final Future<ActorRef> producer = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ProducerRequest(producerParameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });
        

        consumers.foreach(new Foreach<Tuple2<ActorRef, ActorRef>>() {
            public void each(Tuple2<ActorRef, ActorRef> t) {

                producer.foreach(new Foreach<ActorRef>() {
                    public void each(ActorRef p) {
                        p.tell(new Message("@jonas_boner: I'm going surfing".getBytes(utf8Charset), ""));
                    }
                });
            }
        });
    }

    private void topic() throws InterruptedException {

        final ExchangeParameters exchangeParameters = new ExchangeParameters("my_topic_exchange", Topic.getInstance());

        ActorRef bushDeliveryHandler = system.actorOf(props);

        Props obamaProps = new Props(DirectObamaDeliveryHandlerActor.class);
        ActorRef obamaDeliveryHandler = system.actorOf(obamaProps);

        final ConsumerParameters consumer1Parameters = new ConsumerParameters("@george_bush", bushDeliveryHandler, exchangeParameters);
        final ConsumerParameters consumer2Parameters = new ConsumerParameters("@barack_obama", obamaDeliveryHandler, exchangeParameters);
        final ProducerParameters producerParameters = new ProducerParameters(exchangeParameters);

        Future<ActorRef> consumer1 = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ConsumerRequest(consumer1Parameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        Future<ActorRef> consumer2 = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ConsumerRequest(consumer2Parameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        Future<Tuple2<ActorRef,ActorRef>> consumers = consumer1.zip(consumer2);


        final Future<ActorRef> producer = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ProducerRequest(producerParameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });


        consumers.foreach(new Foreach<Tuple2<ActorRef, ActorRef>>() {
            public void each(Tuple2<ActorRef, ActorRef> t) {

                producer.foreach(new Foreach<ActorRef>() {
                    public void each(ActorRef p) {
                        p.tell(new Message("@jonas_boner: You still suck!!".getBytes(utf8Charset), "@george_bush"));
                        p.tell(new Message("@jonas_boner: Yes I can!".getBytes(utf8Charset), "@barack_obama"));
                    }
                });
            }
        });
    }


    private void callback() {

        final CountDownLatch channelCountdown = new CountDownLatch(2);

        final ConnectionParameters connectionParameters = new ConnectionParameters();

        Future<ActorRef> connection = Patterns.ask(amqp, new ConnectionRequest(connectionParameters), timeout).
                mapTo(manifest(ActorRef.class));

        ActorRef channelCallback = system.actorOf(new Props(new UntypedActorFactory() {
            public UntypedActor create() {
                return new ChannelCallbackActor(channelCountdown);
            }
        }));

        final ExchangeParameters exchangeParameters = new ExchangeParameters("my_callback_exchange", Direct.getInstance());
        final ChannelParameters channelParameters = new ChannelParameters(channelCallback);

        ActorRef dummyHandler = system.actorOf(new Props(DummyActor.class));

        final ConsumerParameters consumerParameters = new ConsumerParameters("callback.routing", dummyHandler, exchangeParameters,
                channelParameters);

        Future<ActorRef> consumer = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ConsumerRequest(consumerParameters), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        Future<ActorRef> producer = connection.flatMap(new Mapper<ActorRef, Future<ActorRef>>() {
            public Future<ActorRef> apply(ActorRef c) {
                return Patterns.ask(c, new ProducerRequest(new ProducerParameters(exchangeParameters,
                        channelParameters)), timeout).mapTo(manifest(ActorRef.class));
            }
        });

        // Wait until both channels (producer & consumer) are started before stopping the connection
        try {
            channelCountdown.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
    }


    private ActorRef getCallbackActor(final CountDownLatch latch) {
        return system.actorOf(new Props(new UntypedActorFactory() {
            public UntypedActor create() {
                return new ChannelCallbackActor(latch);
            }
        }));
    }


    static public class DummyActor extends UntypedActor {
        public void onReceive(Object message) throws Exception {
            // not used
        }
    }

    static public class ChannelCallbackActor extends UntypedActor {

        private final CountDownLatch channelCountdown;
        final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        public ChannelCallbackActor(CountDownLatch channelCountdown) {
            this.channelCountdown = channelCountdown;
        }

        public void onReceive(Object message) throws Exception {
            if (message == Started.getInstance()) {
                log.info("### >> Channel callback: Started");
                channelCountdown.countDown();
            } else if (message == Restarting.getInstance()) {
            } else if (message == Starting.getInstance()) {
            } else if (message == Stopped.getInstance()) {
                log.info("### >> Channel callback: Stopped");
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }

    static public class ConnectionCallbackActor extends UntypedActor {

        final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        public void onReceive(Object message) throws Exception {
            if (message instanceof Connected) {
                log.info("### >> Connection callback: Connected!");
            } else if (message == Reconnecting.getInstance()) {
            } else if (message == Disconnected.getInstance()) {
                log.info("### >> Connection callback: Disconnected!");
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }

    static public class DirectDeliveryHandlerActor extends UntypedActor {

        final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        public void onReceive(Object message) throws Exception {
            if (message instanceof Delivery) {
                Delivery delivery = (Delivery) message;
                log.info("### >> @george_bush received message from: {}", new String(delivery.payloadAsArrayBytes(), utf8Charset.name()));
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }

    static public class DirectObamaDeliveryHandlerActor extends UntypedActor {

        final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        public void onReceive(Object message) throws Exception {
            if (message instanceof Delivery) {
                Delivery delivery = (Delivery) message;
                log.info("### >> @barack_obama received message from: {}", new String(delivery.payloadAsArrayBytes(), utf8Charset.name()));
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }
}
