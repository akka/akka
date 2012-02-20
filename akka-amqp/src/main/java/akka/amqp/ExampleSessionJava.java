package akka.amqp;

import akka.actor.*;
import akka.japi.Procedure;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"unchecked"})
public class ExampleSessionJava {

    public static void main(String... args) {
        new ExampleSessionJava();
    }

    public ExampleSessionJava() {
        ActorSystem system = ActorSystem.create();
        printTopic("DIRECT");
        direct(system);

        printTopic("FANOUT");
        fanout(system);

        printTopic("TOPIC");
        topic(system);

        printTopic("CALLBACK");
        callback(system);

        printTopic("EASY STRING PRODUCER AND CONSUMER");
        easyStringProducerConsumer(system);

        printTopic("EASY PROTOBUF PRODUCER AND CONSUMER");
        easyProtobufProducerConsumer(system);

        // postStop everything the amqp tree except the main AMQP supervisor
        // all connections/consumers/producers will be stopped
         AMQP.shutdownAll();
        system.shutdown();

        printTopic("Happy hAkking :-)");

        System.exit(0);
    }

    private void printTopic(String topic) {

        System.out.println("");
        System.out.println("==== " + topic + " ===");
        System.out.println("");
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException ignore) {}
    }

    private void direct(ActorSystem system) {
        // defaults to amqp://guest:guest@localhost:5672/
        ActorRef connection = AMQP.newConnection();

        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_direct_exchange", Direct.getInstance());

        Props props = new Props(DirectDeliveryHandlerActor.class);
        ActorRef deliveryHandler = system.actorOf(props, "deliveryHandlerActor");

        AMQP.ConsumerParameters consumerParameters = new AMQP.ConsumerParameters("some.routing", deliveryHandler, exchangeParameters);

        AMQP.newConsumer(connection, consumerParameters).getOrElse(null);

        ActorRef producer = AMQP.newProducer(connection, new AMQP.ProducerParameters(exchangeParameters)).getOrElse(null);

        producer.tell(new Message("@jonas_boner: You sucked!!".getBytes(), "some.routing"));
    }

    private void fanout(ActorSystem system) {

        // defaults to amqp://guest:guest@localhost:5672/
        ActorRef connection = AMQP.newConnection();

        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_fanout_exchange", Fanout.getInstance());

        Props props = new Props(DirectDeliveryHandlerActor.class);
        ActorRef bushDeliveryHandler = system.actorOf(props);

        props = new Props(DirectObamaDeliveryHandlerActor.class);
        ActorRef obamaDeliveryHandler = system.actorOf(props);

        AMQP.newConsumer(connection, new AMQP.ConsumerParameters("@george_bush", bushDeliveryHandler, exchangeParameters)).getOrElse(null);

        AMQP.newConsumer(connection, new AMQP.ConsumerParameters("@barack_obama", obamaDeliveryHandler, exchangeParameters)).getOrElse(null);

        ActorRef producer = AMQP.newProducer(connection, new AMQP.ProducerParameters(exchangeParameters)).getOrElse(null);

        producer.tell(new Message("@jonas_boner: I'm going surfing".getBytes(), ""));

    }

    private void topic(ActorSystem system) {

            // defaults to amqp://guest:guest@localhost:5672/
        ActorRef connection = AMQP.newConnection();

        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_topic_exchange", Topic.getInstance());

        Props props = new Props(DirectDeliveryHandlerActor.class);
        ActorRef bushDeliveryHandler = system.actorOf(props);

        props = new Props(DirectObamaDeliveryHandlerActor.class);
        ActorRef obamaDeliveryHandler = system.actorOf(props);

        AMQP.newConsumer(connection, new AMQP.ConsumerParameters("@george_bush", bushDeliveryHandler, exchangeParameters)).getOrElse(null);

        AMQP.newConsumer(connection, new AMQP.ConsumerParameters("@barack_obama", obamaDeliveryHandler, exchangeParameters)).getOrElse(null);

        ActorRef producer = AMQP.newProducer(connection, new AMQP.ProducerParameters(exchangeParameters)).getOrElse(null);

        producer.tell(new Message("@jonas_boner: You still suck!!".getBytes(), "@george_bush"));
        producer.tell(new Message("@jonas_boner: Yes I can!".getBytes(), "@barack_obama"));

        }



    private void callback(ActorSystem system) {

        final CountDownLatch channelCountdown = new CountDownLatch(2);

        ActorRef connectionCallback = system.actorOf(new Props(ConnectionCallbackActor.class));

        AMQP.ConnectionParameters connectionParameters = new AMQP.ConnectionParameters(connectionCallback);
        ActorRef connection = AMQP.newConnection(connectionParameters);

        ActorRef channelCallback = system.actorOf(new Props(new UntypedActorFactory() {
            public UntypedActor create() {
                return new ChannelCallbackActor(channelCountdown);
            }
        }));


        AMQP.ExchangeParameters exchangeParameters = new AMQP.ExchangeParameters("my_callback_exchange", Direct.getInstance());
        AMQP.ChannelParameters channelParameters = new AMQP.ChannelParameters(channelCallback);

        ActorRef dummyHandler = system.actorOf(new Props(DummyActor.class));
        AMQP.ConsumerParameters consumerParameters = new AMQP.ConsumerParameters("callback.routing", dummyHandler, exchangeParameters, channelParameters);

        AMQP.newConsumer(connection, consumerParameters).getOrElse(null);

        AMQP.newProducer(connection, new AMQP.ProducerParameters(exchangeParameters, channelParameters)).getOrElse(null);

        // Wait until both channels (producer & consumer) are started before stopping the connection
        try {
            channelCountdown.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {}
    }


    public void easyStringProducerConsumer(ActorSystem system) {
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

        producer.send("This is easy!");

    }

    public void easyProtobufProducerConsumer(ActorSystem system) {

        ActorRef connection = AMQP.newConnection();

        String exchangeName = "easy.protobuf";

        Procedure<AkkaAmqp.TestMessage> procedure = new Procedure<AkkaAmqp.TestMessage>() {
            public void apply(AkkaAmqp.TestMessage message) {
                System.out.println("### >> Received message: " + message);
            }
        };

        AMQP.newProtobufConsumer(connection, procedure, exchangeName, AkkaAmqp.TestMessage.class);

        AMQP.ProducerClient<AkkaAmqp.TestMessage> producerClient = AMQP.newProtobufProducer(connection, exchangeName);

        producerClient.send(AkkaAmqp.TestMessage.newBuilder().setMessage("akka-amqp rocks!").build());
    }


    static public class DummyActor extends UntypedActor {
        public void onReceive(Object message) throws Exception {
            // not used
        }
    }

    static public class ChannelCallbackActor extends UntypedActor {

        private final CountDownLatch channelCountdown;

        public ChannelCallbackActor(CountDownLatch channelCountdown) {
            this.channelCountdown = channelCountdown;
        }

        public void onReceive(Object message) throws Exception {
            if (Started.getInstance().getClass().isAssignableFrom(message.getClass())) {
                System.out.println("### >> Channel callback: Started");
                channelCountdown.countDown();
            } else if (Restarting.getInstance().getClass().isAssignableFrom(message.getClass())) {
            } else if (Stopped.getInstance().getClass().isAssignableFrom(message.getClass())) {
                System.out.println("### >> Channel callback: Stopped");
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }

    static public class ConnectionCallbackActor extends UntypedActor {

        public void onReceive(Object message) throws Exception {
            if (Connected.getInstance().getClass().isAssignableFrom(message.getClass())) {
                System.out.println("### >> Connection callback: Connected!");
            } else if (Reconnecting.getInstance().getClass().isAssignableFrom(message.getClass())) {
            } else if (Disconnected.getInstance().getClass().isAssignableFrom(message.getClass())) {
                System.out.println("### >> Connection callback: Disconnected!");
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }

    static public class DirectDeliveryHandlerActor extends UntypedActor {

        public void onReceive(Object message) throws Exception {
            if (Delivery.class.isAssignableFrom(message.getClass())) {
                Delivery delivery = (Delivery) message;
                System.out.println("### >> @george_bush received message from: " + new String(delivery.payload()));
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }

    static public class DirectObamaDeliveryHandlerActor extends UntypedActor {

        public void onReceive(Object message) throws Exception {
            if (Delivery.class.isAssignableFrom(message.getClass())) {
                Delivery delivery = (Delivery) message;
                System.out.println("### >> @barack_obama received message from: " + new String(delivery.payload()));
            } else throw new IllegalArgumentException("Unknown message: " + message);
        }
    }
}
