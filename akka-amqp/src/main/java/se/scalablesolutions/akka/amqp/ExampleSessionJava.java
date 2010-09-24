package se.scalablesolutions.akka.amqp;

import se.scalablesolutions.akka.actor.ActorRef;
import se.scalablesolutions.akka.actor.ActorRegistry;
import se.scalablesolutions.akka.actor.UntypedActor;
import se.scalablesolutions.akka.actor.UntypedActorFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ExampleSessionJava {

    public static void main(String... args) {
        new ExampleSessionJava();
    }

    public ExampleSessionJava() {
        printTopic("DIRECT");
        direct();

        printTopic("CALLBACK");
        callback();

        printTopic("Happy hAkking :-)");

        // postStop everything the amqp tree except the main AMQP supervisor
        // all connections/consumers/producers will be stopped
        AMQP.shutdownAll();

        ActorRegistry.shutdownAll();
        System.exit(0);
    }

    private void printTopic(String topic) {

        System.out.println("");
        System.out.println("==== " + topic + " ===");
        System.out.println("");
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException ignore) {
        }
    }

    private void direct() {
        // defaults to amqp://guest:guest@localhost:5672/
        ActorRef connection = AMQP.newConnection();

        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_direct_exchange", ExchangeTypes.DIRECT);

        ActorRef deliveryHandler = UntypedActor.actorOf(DirectDeliveryHandlerActor.class);

        AMQP.ConsumerParameters consumerParameters = new AMQP.ConsumerParameters("some.routing", deliveryHandler, exchangeParameters);
        ActorRef consumer = AMQP.newConsumer(connection, consumerParameters);


        ActorRef producer = AMQP.newProducer(connection, new AMQP.ProducerParameters(exchangeParameters));
        producer.sendOneWay(new Message("@jonas_boner: You sucked!!".getBytes(), "some.routing"));
    }

    private void callback() {

        final CountDownLatch channelCountdown = new CountDownLatch(2);

        ActorRef connectionCallback = UntypedActor.actorOf(ConnectionCallbackActor.class);
        connectionCallback.start();

        AMQP.ConnectionParameters connectionParameters = new AMQP.ConnectionParameters(connectionCallback);
        ActorRef connection = AMQP.newConnection(connectionParameters);

        ActorRef channelCallback = UntypedActor.actorOf(new UntypedActorFactory() {
            public UntypedActor create() {
                return new ChannelCallbackActor(channelCountdown);
            }
        });
        channelCallback.start();

        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_callback_exchange", ExchangeTypes.DIRECT);
        AMQP.ChannelParameters channelParameters = new AMQP.ChannelParameters(channelCallback);

        ActorRef dummyHandler = UntypedActor.actorOf(DummyActor.class);
        AMQP.ConsumerParameters consumerParameters = new AMQP.ConsumerParameters("callback.routing", dummyHandler, exchangeParameters, channelParameters);

        ActorRef consumer = AMQP.newConsumer(connection, consumerParameters);

        ActorRef producer = AMQP.newProducer(connection, new AMQP.ProducerParameters(exchangeParameters));

        // Wait until both channels (producer & consumer) are started before stopping the connection
        try {
            channelCountdown.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
        connection.stop();
    }
}

class DummyActor extends UntypedActor {
    public void onReceive(Object message) throws Exception {
        // not used
    }
}

class ChannelCallbackActor extends UntypedActor {

    private final CountDownLatch channelCountdown;

    public ChannelCallbackActor(CountDownLatch channelCountdown) {
        this.channelCountdown = channelCountdown;
    }

    public void onReceive(Object message) throws Exception {
        if (ChannelCallbacks.STARTED.getClass().isAssignableFrom(message.getClass())) {
            System.out.println("### >> Channel callback: Started");
            channelCountdown.countDown();
        } else if (ChannelCallbacks.RESTARTING.getClass().isAssignableFrom(message.getClass())) {
        } else if (ChannelCallbacks.STOPPED.getClass().isAssignableFrom(message.getClass())) {
            System.out.println("### >> Channel callback: Stopped");
        } else throw new IllegalArgumentException("Unknown message: " + message);
    }
}

class ConnectionCallbackActor extends UntypedActor {

    public void onReceive(Object message) throws Exception {
        if (ConnectionCallbacks.CONNECTED.getClass().isAssignableFrom(message.getClass())) {
            System.out.println("### >> Connection callback: Connected!");
        } else if (ConnectionCallbacks.RECONNECTING.getClass().isAssignableFrom(message.getClass())) {
        } else if (ConnectionCallbacks.DISCONNECTED.getClass().isAssignableFrom(message.getClass())) {
            System.out.println("### >> Connection callback: Disconnected!");
        } else throw new IllegalArgumentException("Unknown message: " + message);
    }
}

class DirectDeliveryHandlerActor extends UntypedActor {

    public void onReceive(Object message) throws Exception {
        if (Delivery.class.isAssignableFrom(message.getClass())) {
            Delivery delivery = (Delivery) message;
            System.out.println("### >> @george_bush received message from: " + new String(delivery.payload()));
        } else throw new IllegalArgumentException("Unknown message: " + message);
    }
}
