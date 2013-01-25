package sample.cluster.stats.japi;

import scala.concurrent.Future;
import sample.cluster.stats.japi.StatsMessages.JobFailed;
import sample.cluster.stats.japi.StatsMessages.StatsJob;
import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.UntypedActor;
import akka.dispatch.Recover;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.LeaderChanged;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.util.Timeout;
import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;
import static java.util.concurrent.TimeUnit.SECONDS;

//#facade
public class StatsFacade extends UntypedActor {

  LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  Cluster cluster = Cluster.get(getContext().system());

  Address currentMaster = null;

  //subscribe to cluster changes, MemberEvent
  @Override
  public void preStart() {
    cluster.subscribe(getSelf(), LeaderChanged.class);
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
      StatsJob job = (StatsJob) message;
      ActorRef service = getContext().actorFor(currentMaster +
          "/user/singleton/statsService");
      Future<Object> f = ask(service, job, new Timeout(5, SECONDS)).recover(
          new Recover<Object>() {
            public Object recover(Throwable t) {
              return new JobFailed("Service unavailable, try again later");
            }
          }, getContext().dispatcher());
      pipe(f, getContext().dispatcher()).to(getSender());

    } else if (message instanceof CurrentClusterState) {
      CurrentClusterState state = (CurrentClusterState) message;
      currentMaster = state.getLeader();

    } else if (message instanceof LeaderChanged) {
      LeaderChanged leaderChanged = (LeaderChanged) message;
      currentMaster = leaderChanged.getLeader();

    } else {
      unhandled(message);
    }
  }

}
//#facade
