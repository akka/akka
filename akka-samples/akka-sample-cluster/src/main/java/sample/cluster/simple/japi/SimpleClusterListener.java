package sample.cluster.simple.japi;

import akka.actor.UntypedActor;
import akka.cluster.ClusterEvent.ClusterDomainEvent;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberJoined;
import akka.cluster.ClusterEvent.MemberUnreachable;
import akka.cluster.ClusterEvent.MemberUp;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class SimpleClusterListener extends UntypedActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  @Override
  public void onReceive(Object message) {
    if (message instanceof CurrentClusterState) {
      CurrentClusterState state = (CurrentClusterState) message;
      log.info("Current members: {}", state.members());

    } else if (message instanceof MemberJoined) {
      MemberJoined mJoined = (MemberJoined) message;
      log.info("Member joined: {}", mJoined);

    } else if (message instanceof MemberUp) {
      MemberUp mUp = (MemberUp) message;
      log.info("Member is Up: {}", mUp.member());

    } else if (message instanceof MemberUnreachable) {
      MemberUnreachable mUnreachable = (MemberUnreachable) message;
      log.info("Member detected as unreachable: {}", mUnreachable.member());

    } else if (message instanceof ClusterDomainEvent) {
      // ignore

    } else {
      unhandled(message);
    }

  }
}
