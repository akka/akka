/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io;

//#imports
import akka.actor.ActorRef;
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.io.Inet;
import akka.io.Udp;
import akka.io.UdpMessage;
import akka.util.ByteString;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.DatagramSocket;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
//#imports

public class JavaUdpMulticast {
    //#inet6-protocol-family
    public static class Inet6ProtocolFamily extends Inet.DatagramChannelCreator {
        @Override
        public DatagramChannel create() throws Exception {
            return DatagramChannel.open(StandardProtocolFamily.INET6);
        }
    }
    //#inet6-protocol-family

    //#multicast-group
    public static class MulticastGroup extends Inet.AbstractSocketOptionV2 {
        private String address;
        private String interf;

        public MulticastGroup(String address, String interf) {
            this.address = address;
            this.interf = interf;
        }

        @Override
        public void afterBind(DatagramSocket s) {
            try {
                InetAddress group = InetAddress.getByName(address);
                NetworkInterface networkInterface = NetworkInterface.getByName(interf);
                s.getChannel().join(group, networkInterface);
            } catch (Exception ex) {
                System.out.println("Unable to join multicast group.");
            }
        }
    }
    //#multicast-group

    public static class Listener extends AbstractActor {
        LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        ActorRef sink;

        public Listener(String iface, String group, Integer port, ActorRef sink) {
            this.sink = sink;

            //#bind
            List<Inet.SocketOption> options = new ArrayList<>();
            options.add(new Inet6ProtocolFamily());
            options.add(new MulticastGroup(group, iface));

            final ActorRef mgr = Udp.get(getContext().system()).getManager();
            // listen for datagrams on this address
            InetSocketAddress endpoint = new InetSocketAddress(port);
            mgr.tell(UdpMessage.bind(self(), endpoint, options), self());
            //#bind
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                .match(Udp.Bound.class, bound -> {
                  log.info("Bound to {}", bound.localAddress());
                  sink.tell(bound, self());
                })
                .match(Udp.Received.class, received -> {
                  final String txt = received.data().decodeString("utf-8");
                  log.info("Received '{}' from {}", txt, received.sender());
                  sink.tell(txt, self());
                })
                .build();
        }
    }

    public static class Sender extends AbstractActor {
        LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        String iface;
        String group;
        Integer port;
        String message;

        public Sender(String iface, String group, Integer port, String msg) {
            this.iface = iface;
            this.group = group;
            this.port = port;
            this.message = msg;

            List<Inet.SocketOption> options = new ArrayList<>();
            options.add(new Inet6ProtocolFamily());

            final ActorRef mgr = Udp.get(getContext().system()).getManager();
            mgr.tell(UdpMessage.simpleSender(options), self());
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                .match(Udp.SimpleSenderReady.class, x -> {
                    InetSocketAddress remote = new InetSocketAddress(group + "%" + iface, port);
                    log.info("Sending message to " + remote);
                    sender().tell(UdpMessage.send(ByteString.fromString(message), remote), self());
                })
                .build();
        }
    }
}
