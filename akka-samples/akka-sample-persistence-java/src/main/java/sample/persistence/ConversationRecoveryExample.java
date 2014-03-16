package sample.persistence;

import akka.actor.*;
import akka.persistence.*;

public class ConversationRecoveryExample {
    public static String PING = "PING";
    public static String PONG = "PONG";

    public static class Ping extends UntypedProcessor {
        private final ActorRef pongChannel = getContext().actorOf(Channel.props(), "pongChannel");
        private int counter = 0;

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof ConfirmablePersistent) {
                ConfirmablePersistent msg = (ConfirmablePersistent)message;
                if (msg.payload().equals(PING)) {
                    counter += 1;
                    System.out.println(String.format("received ping %d times", counter));
                    msg.confirm();
                    if (!recoveryRunning()) Thread.sleep(1000);
                    pongChannel.tell(Deliver.create(msg.withPayload(PONG), getSender().path()), getSelf());
                }
            } else if (message.equals("init") && counter == 0) {
                pongChannel.tell(Deliver.create(Persistent.create(PONG), getSender().path()), getSelf());
            }
        }
    }

    public static class Pong extends UntypedProcessor {
        private final ActorRef pingChannel = getContext().actorOf(Channel.props(), "pingChannel");
        private int counter = 0;

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof ConfirmablePersistent) {
                ConfirmablePersistent msg = (ConfirmablePersistent)message;
                if (msg.payload().equals(PONG)) {
                    counter += 1;
                    System.out.println(String.format("received pong %d times", counter));
                    msg.confirm();
                    if (!recoveryRunning()) Thread.sleep(1000);
                    pingChannel.tell(Deliver.create(msg.withPayload(PING), getSender().path()), getSelf());
                }
            }
        }
    }

    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("example");

        final ActorRef ping = system.actorOf(Props.create(Ping.class), "ping");
        final ActorRef pong = system.actorOf(Props.create(Pong.class), "pong");

        ping.tell("init", pong);
    }
}
