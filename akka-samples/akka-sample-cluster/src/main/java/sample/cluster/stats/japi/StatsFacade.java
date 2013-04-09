package sample.cluster.stats.japi;

import sample.cluster.stats.japi.StatsMessages.JobFailed;
import sample.cluster.stats.japi.StatsMessages.StatsJob;
import akka.actor.ActorSelection;
import akka.actor.Address;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.RoleLeaderChanged;
import akka.event.Logging;
import akka.event.LoggingAdapter;


//#facade
public class StatsFacade extends UntypedActor {

  LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  Cluster cluster = Cluster.get(getContext().system());

  ActorSelection currentMaster = null;

  //subscribe to cluster changes, RoleLeaderChanged
  @Override
  public void preStart() {
    cluster.subscribe(getSelf(), RoleLeaderChanged.class);
  }

  //re-subscribe when restart
  @Override
  public void postStop() {
    cluster.unsubscribe(getSelf());
  }

  @Override
  public void onReceive(Object message) {
    if (message instanceof StatsJob && currentMaster == null) {
      getSender().tell(new JobFailed("Service unavailable, try again later"),
          getSelf());

    } else if (message instanceof StatsJob) {
      currentMaster.tell(message, getSender());

    } else if (message instanceof CurrentClusterState) {
      CurrentClusterState state = (CurrentClusterState) message;
      setCurrentMaster(state.getRoleLeader("compute"));

    } else if (message instanceof RoleLeaderChanged) {
      RoleLeaderChanged leaderChanged = (RoleLeaderChanged) message;
      if (leaderChanged.role().equals("compute"))
        setCurrentMaster(leaderChanged.getLeader());

    } else {
      unhandled(message);
    }
  }

  void setCurrentMaster(Address address) {
    if (address == null)
      currentMaster = null;
    else
      currentMaster = getContext().actorSelection(address +
          "/user/singleton/statsService");
  }

}
//#facade
