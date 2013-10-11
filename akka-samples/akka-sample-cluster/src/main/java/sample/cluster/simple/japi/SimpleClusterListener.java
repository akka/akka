package sample.cluster.simple.japi;

import akka.actor.UntypedActor;
import akka.cluster.ClusterEvent.ClusterDomainEvent;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.ClusterEvent.UnreachableMember;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class SimpleClusterListener extends UntypedActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

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

    } else if (message instanceof ClusterDomainEvent) {
      // ignore

    } else {
      unhandled(message);
    }

  }
}
