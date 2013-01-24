package sample.cluster.factorial.japi;

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberUp;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class StartupFrontend extends UntypedActor {
  final int upToN;
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  int memberCount = 0;

  public StartupFrontend(int upToN) {
    this.upToN = upToN;    
  }

  //subscribe to ClusterMetricsChanged
  @Override
  public void preStart() {
    log.info("Factorials will start when 3 members in the cluster.");
    Cluster.get(getContext().system()).subscribe(getSelf(), MemberUp.class);
  }

  @Override
  public void onReceive(Object message) {
    if (message instanceof CurrentClusterState) {
      CurrentClusterState state = (CurrentClusterState) message;
      memberCount = state.members().size();
      runWhenReady();

    } else if (message instanceof MemberUp) {
      memberCount++;
      runWhenReady();

    } else {
      unhandled(message);
    }

  }

  void runWhenReady() {
    if (memberCount >= 3) {
      getContext().system().actorOf(new Props(new UntypedActorFactory() {
        @Override
        public UntypedActor create() {
          return new FactorialFrontend(upToN, true);
        }
      }), "factorialFrontend");
      getContext().stop(getSelf());
    }
  }
}
