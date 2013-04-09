/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io;

//#imports
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import akka.io.Inet;
import akka.io.Udp;
import akka.io.UdpMessage;
import akka.io.UdpSO;
import akka.util.ByteString;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
//#imports

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;


public class UdpDocTest {
    static public class Demo extends UntypedActor {
        public void onReceive(Object message) {
            //#manager
            final ActorRef udp = Udp.get(system).manager();
            //#manager

            //#simplesend
            udp.tell(UdpMessage.simpleSender(), getSelf());

            // ... or with socket options:
            final List<Inet.SocketOption> options = new ArrayList<Inet.SocketOption>();
            options.add(UdpSO.broadcast(true));
            udp.tell(UdpMessage.simpleSender(), getSelf());
            //#simplesend

            ActorRef simpleSender = null;

            //#simplesend-finish
            if (message instanceof Udp.SimpleSendReady) {
                simpleSender = getSender();
            }
            //#simplesend-finish

            final ByteString data = ByteString.empty();

            //#simplesend-send
            simpleSender.tell(UdpMessage.send(data, new InetSocketAddress("127.0.0.1", 7654)), getSelf());
            //#simplesend-send

            final ActorRef handler = getSelf();

            //#bind
            udp.tell(UdpMessage.bind(handler, new InetSocketAddress("127.0.0.1", 9876)), getSelf());
            //#bind

            ActorRef udpWorker = null;

            //#bind-finish
            if (message instanceof Udp.Bound) {
                udpWorker = getSender();
            }
            //#bind-finish

            //#bind-receive
            if (message instanceof Udp.Received) {
                final Udp.Received rcvd = (Udp.Received) message;
                final ByteString payload = rcvd.data();
                final InetSocketAddress sender = rcvd.sender();
            }
            //#bind-receive

            //#bind-send
            udpWorker.tell(UdpMessage.send(data, new InetSocketAddress("127.0.0.1", 7654)), getSelf());
            //#bind-send
        }
    }

    static ActorSystem system;

    @BeforeClass
    static public void setup() {
        system = ActorSystem.create("IODocTest");
    }

    @AfterClass
    static public void teardown() {
        system.shutdown();
    }

    @Test
    public void demonstrateConnect() {
    }

}
