package se.scalablesolutions.akka.amqp;

// Needed for Java API usage
public final class ExchangeTypes {
    public static final ExchangeType DIRECT = Direct$.MODULE$;
    public static final ExchangeType FANOUT = Fanout$.MODULE$;
    public static final ExchangeType TOPIC = Topic$.MODULE$;
    public static final ExchangeType MATCH = Match$.MODULE$;

    private ExchangeTypes() {}
}
