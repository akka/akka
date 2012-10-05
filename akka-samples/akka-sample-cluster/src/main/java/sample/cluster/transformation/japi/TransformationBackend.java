package sample.cluster.transformation.japi;

import static sample.cluster.transformation.japi.TransformationMessages.BACKEND_REGISTRATION;
import sample.cluster.transformation.japi.TransformationMessages.TransformationJob;
import sample.cluster.transformation.japi.TransformationMessages.TransformationResult;
//#imports
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberEvent;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
//#imports

//#backend
public class TransformationBackend extends UntypedActor {

  Cluster cluster = Cluster.get(getContext().system());

  //subscribe to cluster changes, MemberEvent
  @Override
  public void preStart() {
    cluster.subscribe(getSelf(), MemberEvent.class);
  }

  //re-subscribe when restart
  @Override
  public void postStop() {
    cluster.unsubscribe(getSelf());
  }

  @Override
  public void onReceive(Object message) {
    if (message instanceof TransformationJob) {
      TransformationJob job = (TransformationJob) message;
      getSender()
          .tell(new TransformationResult(job.getText().toUpperCase()),
              getSelf());

    } else if (message instanceof CurrentClusterState) {
      CurrentClusterState state = (CurrentClusterState) message;
      for (Member member : state.getMembers()) {
        if (member.status().equals(MemberStatus.up())) {
          register(member);
        }
      }

    } else if (message instanceof MemberUp) {
      MemberUp mUp = (MemberUp) message;
      register(mUp.member());

    } else {
      unhandled(message);
    }
  }

  //try to register to all nodes, even though there
  // might not be any frontend on all nodes
  void register(Member member) {
    getContext().actorFor(member.address() + "/user/frontend").tell(
        BACKEND_REGISTRATION, getSelf());
  }
}
//#backend
