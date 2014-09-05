/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.io.Udp;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Random;

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

            // host assigned link local multicast address http://tools.ietf.org/html/rfc3307#section-4.3.2
            // generate a random 32 bit multicast address with the high order bit set
            final String randomAddress = Long.toHexString(((long) Math.abs(new Random().nextInt())) | (1L << 31)).toUpperCase();
            final StringBuilder groupBuilder = new StringBuilder("FF02:");
            for (int i = 0; i < 2; i += 1) {
                groupBuilder.append(":");
                groupBuilder.append(randomAddress.subSequence(i * 4, i * 4 + 4));
            }
            final String group = groupBuilder.toString();
            final Integer port = TestUtils.temporaryUdpIpv6Port(ipv6Iface);
            final String msg = "ohi";
            final ActorRef sink = getRef();
            final String iface = ipv6Iface.getName();

            final ActorRef listener = system.actorOf(Props.create(JavaUdpMulticast.Listener.class, iface, group, port, sink));
            expectMsgClass(Udp.Bound.class);
            final ActorRef sender = system.actorOf(Props.create(JavaUdpMulticast.Sender.class, iface, group, port, msg));
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
