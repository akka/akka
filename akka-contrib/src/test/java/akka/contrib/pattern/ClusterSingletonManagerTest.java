/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.Member;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.ClusterEvent.MemberEvent;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.ClusterEvent.MemberRemoved;

public class ClusterSingletonManagerTest {

  public void demo() {
    final ActorSystem system = null;
    final ActorRef queue = null;
    final ActorRef testActor = null;

    //#create-singleton-manager
    system.actorOf(
      ClusterSingletonManager.defaultProps("consumer", new End(), "worker",
        new ClusterSingletonPropsFactory() {
          @Override
          public Props create(Object handOverData) {
            return Props.create(Consumer.class, handOverData, queue, testActor);
          }
        }), "singleton");
    //#create-singleton-manager
  }

  static//documentation of how to keep track of the oldest member in user land
  //#singleton-proxy
  public class ConsumerProxy extends UntypedActor {

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

    final String role = "worker";

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
      if (message instanceof CurrentClusterState) {
        CurrentClusterState state = (CurrentClusterState) message;
        List<Member> members = new ArrayList<Member>();
        for (Member m : state.getMembers()) {
          if (m.hasRole(role))
            members.add(m);
        }
        membersByAge.clear();
        membersByAge.addAll(members);

      } else if (message instanceof MemberUp) {
        Member m = ((MemberUp) message).member();
        if (m.hasRole(role))
          membersByAge.add(m);

      } else if (message instanceof MemberRemoved) {
        Member m = ((MemberUp) message).member();
        if (m.hasRole(role))
          membersByAge.remove(m);

      } else if (message instanceof MemberEvent) {
        // not interesting

      } else if (!membersByAge.isEmpty()) {
        currentMaster().tell(message, getSender());

      }
    }

    ActorSelection currentMaster() {
      return getContext().actorSelection(membersByAge.first().address() +
          "/user/singleton/statsService");
    }

  }
  //#singleton-proxy


  public static class End {}

  public static class Consumer {}
}
