package se.scalablesolutions.akka.amqp;

public final class ChannelCallbacks {

    public static final AMQPMessage STARTED = Started$.MODULE$;
    public static final AMQPMessage RESTARTING = Restarting$.MODULE$;
    public static final AMQPMessage STOPPED = Stopped$.MODULE$;

    private ChannelCallbacks() {}
}
