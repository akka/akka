package se.scalablesolutions.akka.amqp;

import se.scalablesolutions.akka.actor.ActorRef;
import se.scalablesolutions.akka.actor.ActorRegistry;
import se.scalablesolutions.akka.actor.UntypedActor;
import se.scalablesolutions.akka.actor.UntypedActorFactory;

import se.scalablesolutions.akka.remote.protocol.RemoteProtocol;
import se.scalablesolutions.akka.util.Procedure;

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

        printTopic("EASY STRING PRODUCER AND CONSUMER");
        easyStringProducerConsumer();

        printTopic("EASY PROTOBUF PRODUCER AND CONSUMER");
        easyProtobufProducerConsumer();


        // postStop everything the amqp tree except the main AMQP supervisor
        // all connections/consumers/producers will be stopped
        AMQP.shutdownAll();

        ActorRegistry.shutdownAll();

        printTopic("Happy hAkking :-)");

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

        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_direct_exchange", new ExchangeType.Direct());

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

        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_callback_exchange", new ExchangeType.Direct());
        AMQP.ChannelParameters channelParameters = new AMQP.ChannelParameters(channelCallback);

        ActorRef dummyHandler = UntypedActor.actorOf(DummyActor.class);
        AMQP.ConsumerParameters consumerParameters = new AMQP.ConsumerParameters("callback.routing", dummyHandler, exchangeParameters, channelParameters);

        ActorRef consumer = AMQP.newConsumer(connection, consumerParameters);

        ActorRef producer = AMQP.newProducer(connection, new AMQP.ProducerParameters(exchangeParameters, channelParameters));

        // Wait until both channels (producer & consumer) are started before stopping the connection
        try {
            channelCountdown.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
        connection.stop();
    }

    public void easyStringProducerConsumer() {
        ActorRef connection = AMQP.newConnection();

        String exchangeName = "easy.string";

        // listen by default to:
        // exchange = optional exchangeName
        // routingKey = provided routingKey or <exchangeName>.request
        // queueName = <routingKey>.in
        Procedure<String> procedure = new Procedure<String>() {
            public void apply(String message) {
                System.out.println("### >> Received message: " + message);
            }
        };
        AMQP.newStringConsumer(connection, procedure, exchangeName);

        // send by default to:
        // exchange = exchangeName
        // routingKey = <exchange>.request
        AMQP.ProducerClient<String> producer = AMQP.newStringProducer(connection, exchangeName);

        producer.send("This shit is easy!");
    }

    public void easyProtobufProducerConsumer() {

        ActorRef connection = AMQP.newConnection();

        String exchangeName = "easy.protobuf";
        
        Procedure<RemoteProtocol.AddressProtocol> procedure = new Procedure<RemoteProtocol.AddressProtocol>() {
            public void apply(RemoteProtocol.AddressProtocol message) {
                System.out.println("### >> Received message: " + message);
            }
        };

        AMQP.newProtobufConsumer(connection, procedure, exchangeName, RemoteProtocol.AddressProtocol.class);

        AMQP.ProducerClient<RemoteProtocol.AddressProtocol> producerClient = AMQP.newProtobufProducer(connection, exchangeName);

        producerClient.send(RemoteProtocol.AddressProtocol.newBuilder().setHostname("akkarocks.com").setPort(1234).build());
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
        if (Started.class.isAssignableFrom(message.getClass())) {
            System.out.println("### >> Channel callback: Started");
            channelCountdown.countDown();
        } else if (Restarting.class.isAssignableFrom(message.getClass())) {
        } else if (Stopped.class.isAssignableFrom(message.getClass())) {
            System.out.println("### >> Channel callback: Stopped");
        } else throw new IllegalArgumentException("Unknown message: " + message);
    }
}

class ConnectionCallbackActor extends UntypedActor {

    public void onReceive(Object message) throws Exception {
        if (Connected.class.isAssignableFrom(message.getClass())) {
            System.out.println("### >> Connection callback: Connected!");
        } else if (Reconnecting.class.isAssignableFrom(message.getClass())) {
        } else if (Disconnected.class.isAssignableFrom(message.getClass())) {
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
