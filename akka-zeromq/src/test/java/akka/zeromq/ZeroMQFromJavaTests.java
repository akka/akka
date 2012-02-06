/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq;

import static org.junit.Assert.*;
import org.junit.Test;

public class ZeroMQFromJavaTests {
    @Test
    public void checkObjectHelperMethods() {
        assertTrue(ZeroMQ.connecting() == Connecting$.MODULE$);
        assertTrue(ZeroMQ.closed() == Closed$.MODULE$);
        assertTrue(ZeroMQ.affinity() == Affinity$.MODULE$);
        assertTrue(ZeroMQ.backlog() == Backlog$.MODULE$);
        assertTrue(ZeroMQ.fileDescriptor() == FileDescriptor$.MODULE$);
        assertTrue(ZeroMQ.identity() == Identity$.MODULE$);
        assertTrue(ZeroMQ.linger() == Linger$.MODULE$);
        assertTrue(ZeroMQ.maxMessageSize() == MaxMsgSize$.MODULE$);
        assertTrue(ZeroMQ.multicastHops() == MulticastHops$.MODULE$);
        assertTrue(ZeroMQ.multicastLoop() == MulticastLoop$.MODULE$);
        assertTrue(ZeroMQ.rate() == Rate$.MODULE$);
        assertTrue(ZeroMQ.receiveBufferSize() == ReceiveBufferSize$.MODULE$);
        assertTrue(ZeroMQ.receiveHighWatermark() == ReceiveHighWatermark$.MODULE$);
        assertTrue(ZeroMQ.reconnectIVL() == ReconnectIVL$.MODULE$);
        assertTrue(ZeroMQ.reconnectIVLMax() == ReconnectIVLMax$.MODULE$);
        assertTrue(ZeroMQ.recoveryInterval() == RecoveryInterval$.MODULE$);
        assertTrue(ZeroMQ.sendBufferSize() == SendBufferSize$.MODULE$);
        assertTrue(ZeroMQ.sendHighWatermark() == SendHighWatermark$.MODULE$);
        assertTrue(ZeroMQ.swap() == Swap$.MODULE$);
    }
}
