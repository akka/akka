package docs.io;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.io.Inet;
import akka.io.Tcp;
import akka.io.TcpMessage;
import akka.util.ByteString;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
public class JavaReadBackPressure {

    static public class Listener extends UntypedActor {
        ActorRef tcp;
        ActorRef listener;

        @Override
        //#pull-accepting
        public void onReceive(Object message) throws Exception {
            if (message instanceof Tcp.Bound) {
                listener = sender();
                // Accept connections one by one
                listener.tell(TcpMessage.resumeAccepting(1), self());
            } else if (message instanceof Tcp.Connected) {
                ActorRef handler = getContext().actorOf(Props.create(PullEcho.class, sender()));
                sender().tell(TcpMessage.register(handler), self());
                // Resume accepting connections
                listener.tell(TcpMessage.resumeAccepting(1), self());
            }
        }
        //#pull-accepting

        @Override
        public void preStart() throws Exception {
            //#pull-mode-bind
            tcp = Tcp.get(getContext().system()).manager();
            final List<Inet.SocketOption> options = new ArrayList<Inet.SocketOption>();
            tcp.tell(
               TcpMessage.bind(self(), new InetSocketAddress("localhost", 0), 100, options, true),
               self()
            );
            //#pull-mode-bind
        }

        private void demonstrateConnect() {
            //#pull-mode-connect
            final List<Inet.SocketOption> options = new ArrayList<Inet.SocketOption>();
            tcp.tell(
               TcpMessage.connect(new InetSocketAddress("localhost", 3000), null, options, null, true),
               self()
            );
            //#pull-mode-connect
        }
    }

    static public class Ack implements Tcp.Event {
    }

    static public class PullEcho extends UntypedActor {
        final ActorRef connection;

        public PullEcho(ActorRef connection) {
            this.connection = connection;
        }

        //#pull-reading-echo
        @Override
        public void preStart() throws Exception {
            connection.tell(TcpMessage.resumeReading(), self());
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Tcp.Received) {
                ByteString data = ((Tcp.Received) message).data();
                connection.tell(TcpMessage.write(data, new Ack()), self());
            } else if (message instanceof Ack) {
                connection.tell(TcpMessage.resumeReading(), self());
            }
        }
        //#pull-reading-echo


    }

}
