package sample.cluster.simple;

import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberEvent;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.ClusterEvent.UnreachableMember;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class SimpleClusterListener2 extends UntypedActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  Cluster cluster = Cluster.get(getContext().system());

  //subscribe to cluster changes
  @Override
  public void preStart() {
    //#subscribe
    cluster.subscribe(getSelf(), MemberEvent.class, UnreachableMember.class);
    //#subscribe
  }

  //re-subscribe when restart
  @Override
  public void postStop() {
    cluster.unsubscribe(getSelf());
  }

  @Override
  public void onReceive(Object message) {
    if (message instanceof CurrentClusterState) {
      CurrentClusterState state = (CurrentClusterState) message;
      log.info("Current members: {}", state.members());

    } else if (message instanceof MemberUp) {
      MemberUp mUp = (MemberUp) message;
      log.info("Member is Up: {}", mUp.member());

    } else if (message instanceof UnreachableMember) {
      UnreachableMember mUnreachable = (UnreachableMember) message;
      log.info("Member detected as unreachable: {}", mUnreachable.member());

    } else if (message instanceof MemberRemoved) {
      MemberRemoved mRemoved = (MemberRemoved) message;
      log.info("Member is Removed: {}", mRemoved.member());

    } else if (message instanceof MemberEvent) {
      // ignore

    } else {
      unhandled(message);
    }

  }
}
