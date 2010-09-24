package se.scalablesolutions.akka.amqp;

public final class ConnectionCallbacks {

    public static final AMQPMessage CONNECTED = Connected$.MODULE$;
    public static final AMQPMessage RECONNECTING = Reconnecting$.MODULE$;
    public static final AMQPMessage DISCONNECTED = Disconnected$.MODULE$;

    private ConnectionCallbacks() {}
}
