package akka.zeromq;

/**
 * Java API for akka.zeromq
 */
public class ZeroMQ {

    /**
     * The message that is sent when an ZeroMQ socket connects.
     * <p/>
     * <pre>
     * if (message == connecting()) {
     *   // Socket connected
     * }
     * </pre>
     *
     * @return the single instance of Connecting
     */
    public final static Connecting$ connecting() {
        return Connecting$.MODULE$;
    }

    /**
     * The message that is sent when an ZeroMQ socket disconnects.
     * <p/>
     * <pre>
     * if (message == closed()) {
     *   // Socket disconnected
     * }
     * </pre>
     *
     * @return the single instance of Closed
     */
    public final static Closed$ closed() {
        return Closed$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its affinity configuration.
     * <p/>
     * <pre>
     * socket.ask(affinity())
     * </pre>
     *
     * @return the single instance of Affinity
     */
    public final static Affinity$ affinity() {
        return Affinity$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its backlog configuration.
     * <p/>
     * <pre>
     * socket.ask(backlog())
     * </pre>
     *
     * @return the single instance of Backlog
     */
    public final static Backlog$ backlog() {
        return Backlog$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its file descriptor configuration.
     * <p/>
     * <pre>
     * socket.ask(fileDescriptor())
     * </pre>
     *
     * @return the single instance of FileDescriptor
     */
    public final static FileDescriptor$ fileDescriptor() {
        return FileDescriptor$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its identity configuration.
     * <p/>
     * <pre>
     * socket.ask(identity())
     * </pre>
     *
     * @return the single instance of Identity
     */
    public final static Identity$ identity() {
        return Identity$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its linger configuration.
     * <p/>
     * <pre>
     * socket.ask(linger())
     * </pre>
     *
     * @return the single instance of Linger
     */
    public final static Linger$ linger() {
        return Linger$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its max message size configuration.
     * <p/>
     * <pre>
     * socket.ask(maxMessageSize())
     * </pre>
     *
     * @return the single instance of MaxMsgSize
     */
    public final static MaxMsgSize$ maxMessageSize() {
        return MaxMsgSize$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its multicast hops configuration.
     * <p/>
     * <pre>
     * socket.ask(multicastHops())
     * </pre>
     *
     * @return the single instance of MulticastHops
     */
    public final static MulticastHops$ multicastHops() {
        return MulticastHops$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its multicast loop configuration.
     * <p/>
     * <pre>
     * socket.ask(multicastLoop())
     * </pre>
     *
     * @return the single instance of MulticastLoop
     */
    public final static MulticastLoop$ multicastLoop() {
        return MulticastLoop$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its rate configuration.
     * <p/>
     * <pre>
     * socket.ask(rate())
     * </pre>
     *
     * @return the single instance of Rate
     */
    public final static Rate$ rate() {
        return Rate$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its receive bufferSize configuration.
     * <p/>
     * <pre>
     * socket.ask(receiveBufferSize())
     * </pre>
     *
     * @return the single instance of ReceiveBufferSize
     */
    public final static ReceiveBufferSize$ receiveBufferSize() {
        return ReceiveBufferSize$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its receive high watermark configuration.
     * <p/>
     * <pre>
     * socket.ask(receiveHighWatermark())
     * </pre>
     *
     * @return the single instance of ReceiveHighWatermark
     */
    public final static ReceiveHighWatermark$ receiveHighWatermark() {
        return ReceiveHighWatermark$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its reconnect interval configuration.
     * <p/>
     * <pre>
     * socket.ask(reconnectIVL())
     * </pre>
     *
     * @return the single instance of ReconnectIVL
     */
    public final static ReconnectIVL$ reconnectIVL() {
        return ReconnectIVL$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its max reconnect interval configuration.
     * <p/>
     * <pre>
     * socket.ask(reconnectIVLMax())
     * </pre>
     *
     * @return the single instance of ReconnectIVLMax
     */
    public final static ReconnectIVLMax$ reconnectIVLMax() {
        return ReconnectIVLMax$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its recovery interval configuration.
     * <p/>
     * <pre>
     * socket.ask(recoveryInterval())
     * </pre>
     *
     * @return the single instance of RecoveryInterval
     */
    public final static RecoveryInterval$ recoveryInterval() {
        return RecoveryInterval$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its send buffer size configuration.
     * <p/>
     * <pre>
     * socket.ask(sendBufferSize())
     * </pre>
     *
     * @return the single instance of SendBufferSize
     */
    public final static SendBufferSize$ sendBufferSize() {
        return SendBufferSize$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its send high watermark configuration.
     * <p/>
     * <pre>
     * socket.ask(sendHighWatermark())
     * </pre>
     *
     * @return the single instance of SendHighWatermark
     */
    public final static SendHighWatermark$ sendHighWatermark() {
        return SendHighWatermark$.MODULE$;
    }

    /**
     * The message to ask a ZeroMQ socket for its swap configuration.
     * <p/>
     * <pre>
     * socket.ask(swap())
     * </pre>
     *
     * @return the single instance of Swap
     */
    public final static Swap$ swap() {
        return Swap$.MODULE$;
    }


}
