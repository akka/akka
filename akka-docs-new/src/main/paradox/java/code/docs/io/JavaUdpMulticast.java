/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io;

//#imports
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
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

    public static class Listener extends UntypedActor {
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
            mgr.tell(UdpMessage.bind(getSelf(), endpoint, options), getSelf());
            //#bind
        }

        @Override
        public void onReceive(Object msg) {
            if (msg instanceof Udp.Bound) {
                final Udp.Bound b = (Udp.Bound) msg;
                log.info("Bound to {}", b.localAddress());
                sink.tell(b, getSelf());
            } else if (msg instanceof Udp.Received) {
                final Udp.Received r = (Udp.Received) msg;
                final String txt = r.data().decodeString("utf-8");
                log.info("Received '{}' from {}", txt, r.sender());
                sink.tell(txt, getSelf());
            } else unhandled(msg);
        }
    }

    public static class Sender extends UntypedActor {
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
            mgr.tell(UdpMessage.simpleSender(options), getSelf());
        }

        @Override
        public void onReceive(Object msg) {
            if (msg instanceof Udp.SimpleSenderReady) {
                InetSocketAddress remote = new InetSocketAddress(group + "%" + iface, port);
                log.info("Sending message to " + remote);
                getSender().tell(UdpMessage.send(ByteString.fromString(message), remote), getSelf());
            } else unhandled(msg);
        }
    }
}
