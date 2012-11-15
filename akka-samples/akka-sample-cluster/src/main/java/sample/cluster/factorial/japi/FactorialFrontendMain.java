package sample.cluster.factorial.japi;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;


public class FactorialFrontendMain {

  public static void main(String[] args) throws Exception {
    final int upToN = (args.length == 0 ? 200 : Integer.valueOf(args[0]));

    ActorSystem system = ActorSystem.create("ClusterSystem");

    // start the calculations when there is at least 2 other members
    system.actorOf(new Props(new UntypedActorFactory() {
        @Override
        public UntypedActor create() {
          return new StartupFrontend(upToN);
        }
      }), "startup");
    
  }

}
