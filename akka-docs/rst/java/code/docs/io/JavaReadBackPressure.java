package docs.io;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.AbstractActor;
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

    static public class Listener extends AbstractActor {
        ActorRef tcp;
        ActorRef listener;

        @Override
        //#pull-accepting
        public Receive createReceive() {
            return receiveBuilder()
                .match(Tcp.Bound.class, x -> {
                  listener = sender();
                  // Accept connections one by one
                  listener.tell(TcpMessage.resumeAccepting(1), self());
                })
                .match(Tcp.Connected.class, x -> {
                  ActorRef handler = getContext().actorOf(Props.create(PullEcho.class, sender()));
                  sender().tell(TcpMessage.register(handler), self());
                  // Resume accepting connections
                  listener.tell(TcpMessage.resumeAccepting(1), self());
                })
                .build();
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

    static public class PullEcho extends AbstractActor {
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
        public Receive createReceive() {
            return receiveBuilder()
                .match(Tcp.Received.class, message -> {
                    ByteString data = message.data();
                    connection.tell(TcpMessage.write(data, new Ack()), self());
                })
                .match(Ack.class, message -> {
                    connection.tell(TcpMessage.resumeReading(), self());
                })
                .build();
        }
        //#pull-reading-echo
    }

}
