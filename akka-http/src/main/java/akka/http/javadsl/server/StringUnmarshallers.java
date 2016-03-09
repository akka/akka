package akka.http.javadsl.server;

import java.util.function.Function;

public class StringUnmarshallers {
    /**
     * An unmarshaller that returns the input String unchanged.
     */
    public static final Unmarshaller<String,String> STRING = StringUnmarshaller.sync(Function.identity());

    /**
     * An unmarshaller that parses the input String as a Byte in decimal notation.
     */
    public static final Unmarshaller<String,Byte> BYTE = assumeBoxed(StringUnmarshallerPredef.byteFromStringUnmarshaller());

    /**
     * An unmarshaller that parses the input String as a Short in decimal notation.
     */
    public static final Unmarshaller<String,Short> SHORT = assumeBoxed(StringUnmarshallerPredef.shortFromStringUnmarshaller());
    
    /**
     * An unmarshaller that parses the input String as an Integer in decimal notation.
     */
    public static final Unmarshaller<String,Integer> INTEGER = assumeBoxed(StringUnmarshallerPredef.intFromStringUnmarshaller());

    /**
     * An unmarshaller that parses the input String as a Long in decimal notation.
     */
    public static final Unmarshaller<String,Long> LONG = assumeBoxed(StringUnmarshallerPredef.longFromStringUnmarshaller());

    /**
     * An unmarshaller that parses the input String as a Byte in hexadecimal notation.
     */
    public static final Unmarshaller<String,Byte> BYTE_HEX = assumeBoxed(StringUnmarshallerPredef.HexByte());

    /**
     * An unmarshaller that parses the input String as a Short in hexadecimal notation.
     */
    public static final Unmarshaller<String,Short> SHORT_HEX = assumeBoxed(StringUnmarshallerPredef.HexShort());
    
    /**
     * An unmarshaller that parses the input String as an Integer in hexadecimal notation.
     */
    public static final Unmarshaller<String,Integer> INTEGER_HEX = assumeBoxed(StringUnmarshallerPredef.HexInt());

    /**
     * An unmarshaller that parses the input String as a Long in hexadecimal notation.
     */
    public static final Unmarshaller<String,Long> LONG_HEX = assumeBoxed(StringUnmarshallerPredef.HexLong());
    
    /**
     * An unmarshaller that parses the input String as a Float in decimal notation.
     */
    public static final Unmarshaller<String,Float> FLOAT = assumeBoxed(StringUnmarshallerPredef.floatFromStringUnmarshaller());
    
    /**
     * An unmarshaller that parses the input String as a Double in decimal notation.
     */
    public static final Unmarshaller<String,Double> DOUBLE = assumeBoxed(StringUnmarshallerPredef.doubleFromStringUnmarshaller());
    
    /**
     * An unmarshaller that parses the input String as a Boolean, matching "true", "yes", "on" as true, and "false", "no", "off" as false.
     */
    public static final Unmarshaller<String,Boolean> BOOLEAN = assumeBoxed(StringUnmarshallerPredef.booleanFromStringUnmarshaller());

    /**
     * An unmarshaller that parses the input String as a UUID.
     */
    public static final Unmarshaller<String,java.util.UUID> UUID = StringUnmarshaller.sync(java.util.UUID::fromString);
    
    /**
     * Assume that the given [src] is a marshaller to a Scala boxed primitive type, and coerces into the given Java boxed type T.
     */
    @SuppressWarnings("unchecked")
    private static <T> Unmarshaller<String, T> assumeBoxed(akka.http.scaladsl.unmarshalling.Unmarshaller<String, Object> src) {
        return (Unmarshaller<String, T>) Unmarshaller.wrap(src);
    }
}
