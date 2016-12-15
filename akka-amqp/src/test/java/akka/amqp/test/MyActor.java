package akka.amqp.test;

/**
 * Copyright (c) 2012 John Stanford
 * Date: 3/15/12
 * Time: 5:52 PM
 */

import akka.actor.*;
import akka.amqp.*;
import akka.dispatch.*;

import static akka.japi.Util.manifest;

import akka.event.Logging;
import akka.event.LoggingAdapter;

import static akka.pattern.Patterns.ask;

import akka.util.Duration;
import akka.util.Timeout;
import com.rabbitmq.client.*;
import com.typesafe.config.ConfigFactory;
import scala.Tuple2;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MyActor extends UntypedActor {


    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    SettingsImpl settings = new SettingsImpl(getContext().system().settings().config());
    Timeout timeout = new Timeout(settings.Timeout());
    static Charset utf8Charset = Charset.forName("UTF-8");

    public void onReceive(Object message) throws Exception {
        throw new IllegalArgumentException("Unknown message: " + message);
    }

    Props props = new Props(DirectDeliveryHandlerActor.class);
    ActorRef deliveryHandler = getContext().actorOf(props, "deliveryHandlerActor");

    ActorRef connection = akka.amqp.AMQP.newConnection((ActorContext) getContext(), new ConnectionParameters());


    public MyActor() {
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

            getContext().system().scheduler().scheduleOnce(Duration.create(10000, TimeUnit.MILLISECONDS), new Runnable() {
                @Override
                public void run() {
                    log.debug("***** shutting down");
                    getContext().system().shutdown();
                }
            });

            printTopic("Happy hAkking :-)");
        }
    }

    public void printTopic(String topic) {

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

        Future<ActorRef> consumer = ask(connection, new ConsumerRequest(consumerParameters), timeout).mapTo(manifest(ActorRef.class));

        final Future<ActorRef> producer = ask(connection, new ProducerRequest(producerParameters), timeout).mapTo(manifest(ActorRef.class));

        consumer.foreach(new Foreach<ActorRef>() {
            public void each(ActorRef c) {

                producer.foreach(new Foreach<ActorRef>() {
                    public void each(ActorRef p) {
                        p.tell(new Message("@jxstanford: You sucked!!".getBytes(utf8Charset), "some.routing"));
                    }
                });
            }
        });
    }


    private void fanout() throws InterruptedException {

        final ExchangeParameters exchangeParameters = new ExchangeParameters("my_fanout_exchange", Fanout.getInstance());

        ActorRef bushDeliveryHandler = getContext().system().actorOf(props);

        Props obamaProps = new Props(DirectObamaDeliveryHandlerActor.class);
        ActorRef obamaDeliveryHandler = getContext().system().actorOf(obamaProps);

        final ConsumerParameters consumer1Parameters = new ConsumerParameters("@george_bush", bushDeliveryHandler, exchangeParameters);
        final ConsumerParameters consumer2Parameters = new ConsumerParameters("@barack_obama", obamaDeliveryHandler, exchangeParameters);
        final ProducerParameters producerParameters = new ProducerParameters(exchangeParameters);

        Future<ActorRef> consumer1 = ask(connection, new ConsumerRequest(consumer1Parameters), timeout).mapTo(manifest(ActorRef.class));

        Future<ActorRef> consumer2 = ask(connection, new ConsumerRequest(consumer2Parameters), timeout).mapTo(manifest(ActorRef.class));

        Future<Tuple2<ActorRef, ActorRef>> consumers = consumer1.zip(consumer2);

        final Future<ActorRef> producer = ask(connection, new ProducerRequest(producerParameters), timeout).mapTo(manifest(ActorRef.class));

        consumers.foreach(new Foreach<Tuple2<ActorRef, ActorRef>>() {
            public void each(Tuple2<ActorRef, ActorRef> t) {

                producer.foreach(new Foreach<ActorRef>() {
                    public void each(ActorRef p) {
                        p.tell(new Message("@jxstanford: I'm going surfing".getBytes(utf8Charset), ""));
                    }
                });
            }
        });
    }

    private void topic() throws InterruptedException {

        final ExchangeParameters exchangeParameters = new ExchangeParameters("my_topic_exchange", Topic.getInstance());

        ActorRef bushDeliveryHandler = getContext().actorOf(props);

        Props obamaProps = new Props(DirectObamaDeliveryHandlerActor.class);
        ActorRef obamaDeliveryHandler = getContext().actorOf(obamaProps);

        final ConsumerParameters consumer1Parameters = new ConsumerParameters("@george_bush", bushDeliveryHandler, exchangeParameters);
        final ConsumerParameters consumer2Parameters = new ConsumerParameters("@barack_obama", obamaDeliveryHandler, exchangeParameters);
        final ProducerParameters producerParameters = new ProducerParameters(exchangeParameters);

        Future<ActorRef> consumer1 = ask(connection, new ConsumerRequest(consumer1Parameters), timeout).mapTo(manifest(ActorRef.class));

        Future<ActorRef> consumer2 = ask(connection, new ConsumerRequest(consumer2Parameters), timeout).mapTo(manifest(ActorRef.class));

        Future<Tuple2<ActorRef, ActorRef>> consumers = consumer1.zip(consumer2);

        final Future<ActorRef> producer = ask(connection, new ProducerRequest(producerParameters), timeout).mapTo(manifest(ActorRef.class));

        consumers.foreach(new Foreach<Tuple2<ActorRef, ActorRef>>() {
            public void each(Tuple2<ActorRef, ActorRef> t) {

                producer.foreach(new Foreach<ActorRef>() {
                    public void each(ActorRef p) {
                        p.tell(new Message("@jxstanford: You still suck!!".getBytes(utf8Charset), "@george_bush"));
                        p.tell(new Message("@jxstanford: Yes I can!".getBytes(utf8Charset), "@barack_obama"));
                    }
                });
            }
        });
    }


    private void callback() {

        final CountDownLatch channelCountdown = new CountDownLatch(2);

        ArrayList<com.rabbitmq.client.Address> addrs = new ArrayList<com.rabbitmq.client.Address>();
        com.rabbitmq.client.Address addr = new com.rabbitmq.client.Address("localhost");
        addrs.add(addr);
        final ConnectionParameters connectionParameters = new ConnectionParameters(addrs);

        ActorRef connection = akka.amqp.AMQP.newConnection((ActorContext) getContext(), new ConnectionParameters(), "callback-connection");

        ActorRef channelCallback = getContext().actorOf(new Props(new UntypedActorFactory() {
            public UntypedActor create() {
                return new ChannelCallbackActor(channelCountdown);
            }
        }));

        final ExchangeParameters exchangeParameters = new ExchangeParameters("my_callback_exchange", Direct.getInstance());
        final ChannelParameters channelParameters = new ChannelParameters(channelCallback);

        ActorRef dummyHandler = getContext().actorOf(new Props(DummyActor.class));

        final ConsumerParameters consumerParameters = new ConsumerParameters("callback.routing", dummyHandler, exchangeParameters,
                channelParameters);

        Future<ActorRef> consumer = ask(connection, new ConsumerRequest(consumerParameters), timeout).mapTo(manifest(ActorRef.class));

        Future<ActorRef> producer = ask(connection, new ProducerRequest(new ProducerParameters(exchangeParameters,
                channelParameters)), timeout).mapTo(manifest(ActorRef.class));

        // Wait until both channels (producer & consumer) are started before stopping the connection
        try {
            channelCountdown.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
    }


    private ActorRef getCallbackActor(final CountDownLatch latch) {
        return getContext().actorOf(new Props(new UntypedActorFactory() {
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