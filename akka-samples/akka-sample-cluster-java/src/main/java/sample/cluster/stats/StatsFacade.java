package sample.cluster.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import sample.cluster.stats.StatsMessages.JobFailed;
import sample.cluster.stats.StatsMessages.StatsJob;
import akka.actor.ActorSelection;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberEvent;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.Member;
import akka.event.Logging;
import akka.event.LoggingAdapter;

//#facade
public class StatsFacade extends UntypedActor {

  final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  final Cluster cluster = Cluster.get(getContext().system());

  final Comparator<Member> ageComparator = new Comparator<Member>() {
    public int compare(Member a, Member b) {
      if (a.isOlderThan(b))
        return -1;
      else if (b.isOlderThan(a))
        return 1;
      else
        return 0;
    }
  };
  final SortedSet<Member> membersByAge = new TreeSet<Member>(ageComparator);

  //subscribe to cluster changes
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
    if (message instanceof StatsJob && membersByAge.isEmpty()) {
      getSender().tell(new JobFailed("Service unavailable, try again later"),
          getSelf());

    } else if (message instanceof StatsJob) {
      currentMaster().tell(message, getSender());

    } else if (message instanceof CurrentClusterState) {
      CurrentClusterState state = (CurrentClusterState) message;
      List<Member> members = new ArrayList<Member>();
      for (Member m : state.getMembers()) {
        if (m.hasRole("compute"))
          members.add(m);
      }
      membersByAge.clear();
      membersByAge.addAll(members);

    } else if (message instanceof MemberUp) {
      Member m = ((MemberUp) message).member();
      if (m.hasRole("compute"))
        membersByAge.add(m);

    } else if (message instanceof MemberRemoved) {
      Member m = ((MemberRemoved) message).member();
      if (m.hasRole("compute"))
        membersByAge.remove(m);

    } else if (message instanceof MemberEvent) {
      // not interesting

    } else {
      unhandled(message);
    }
  }

  ActorSelection currentMaster() {
    return getContext().actorSelection(
        membersByAge.first().address() + "/user/singleton/statsService");
  }

}
//#facade
