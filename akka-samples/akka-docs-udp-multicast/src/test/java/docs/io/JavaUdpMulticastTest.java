/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class JavaUdpMulticastTest {

    static ActorSystem system;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create("JavaUdpMulticastTest");
    }

    @Test
    public void testUdpMulticast() throws Exception {
        new JavaTestKit(system) {{
            NetworkInterface ipv6Iface = null;
            for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements() && ipv6Iface == null;) {
                NetworkInterface interf = interfaces.nextElement();
                for (Enumeration<InetAddress> addresses = interf.getInetAddresses(); addresses.hasMoreElements() && ipv6Iface == null;) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address) {
                        ipv6Iface = interf;
                    }
                }
            }

            final String group = "FF33::1200";
            final Integer port = TestUtils.temporaryUdpIpv6Port(ipv6Iface);
            final String msg = "ohi";
            final ActorRef sink = getRef();

            final ActorRef listener = system.actorOf(Props.create(Listener.class, ipv6Iface.getName(), group, port, sink));
            final ActorRef sender = system.actorOf(Props.create(Sender.class, group, port, msg));

            expectMsgEquals(msg);

            // unbind
            system.stop(listener);
        }};
    }

    @AfterClass
    public static void tearDown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }
}
