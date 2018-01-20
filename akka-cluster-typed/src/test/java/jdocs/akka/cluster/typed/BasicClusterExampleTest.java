package jdocs.akka.cluster.typed;

//#cluster-imports

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.cluster.ClusterEvent;
import akka.cluster.typed.*;
//#cluster-imports
import akka.testkit.typed.javadsl.TestProbe;
import docs.akka.cluster.typed.BasicClusterManualSpec;

//FIXME make these tests
public class BasicClusterExampleTest {
  public void clusterApiExample() {
    ActorSystem<Object> system = ActorSystem.create(Behaviors.empty(), "ClusterSystem", BasicClusterManualSpec.clusterConfig());
    ActorSystem<Object> system2 = ActorSystem.create(Behaviors.empty(), "ClusterSystem", BasicClusterManualSpec.clusterConfig());

    try {
      //#cluster-create
      Cluster cluster = Cluster.get(system);
      //#cluster-create
      Cluster cluster2 = Cluster.get(system2);

      //#cluster-join
      cluster.manager().tell(Join.create(cluster.selfMember().address()));
      //#cluster-join

      //#cluster-leave
      cluster2.manager().tell(Leave.create(cluster2.selfMember().address()));
      //#cluster-leave
    } finally {
      system.terminate();
      system2.terminate();
    }
  }

  public void clusterLeave() throws Exception {
    ActorSystem<Object> system = ActorSystem.create(Behaviors.empty(), "ClusterSystem", BasicClusterManualSpec.clusterConfig());
    ActorSystem<Object> system2 = ActorSystem.create(Behaviors.empty(), "ClusterSystem", BasicClusterManualSpec.clusterConfig());

    try {
      Cluster cluster = Cluster.get(system);
      Cluster cluster2 = Cluster.get(system2);

      //#cluster-subscribe
      TestProbe<ClusterEvent.MemberEvent> testProbe = new TestProbe<>(system);
      cluster.subscriptions().tell(Subscribe.create(testProbe.ref(), ClusterEvent.MemberEvent.class));
      //#cluster-subscribe

      //#cluster-leave-example
      cluster.manager().tell(Leave.create(cluster2.selfMember().address()));
      testProbe.expectMsgType(ClusterEvent.MemberLeft.class);
      testProbe.expectMsgType(ClusterEvent.MemberExited.class);
      testProbe.expectMsgType(ClusterEvent.MemberRemoved.class);
      //#cluster-leave-example

    } finally {
      system.terminate();
      system2.terminate();
    }
  }
}
