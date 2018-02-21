package jdocs.akka.cluster.typed;

//#cluster-imports

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.cluster.ClusterEvent;
import akka.cluster.typed.*;
//#cluster-imports
import akka.testkit.typed.javadsl.TestProbe;
import docs.akka.cluster.typed.BasicClusterManualSpec;

// FIXME these tests are awaiting typed Java testkit to be able to await cluster forming like in BasicClusterExampleSpec
public class BasicClusterExampleTest { // extends JUnitSuite {

  // @Test
  public void clusterApiExample() {
    ActorSystem<Object> system = ActorSystem.create(Behaviors.empty(), "ClusterSystem",
        BasicClusterManualSpec.noPort().withFallback(BasicClusterManualSpec.clusterConfig()));
    ActorSystem<Object> system2 = ActorSystem.create(Behaviors.empty(), "ClusterSystem",
        BasicClusterManualSpec.noPort().withFallback(BasicClusterManualSpec.clusterConfig()));

    try {
      //#cluster-create
      Cluster cluster = Cluster.get(system);
      //#cluster-create
      Cluster cluster2 = Cluster.get(system2);

      //#cluster-join
      cluster.manager().tell(Join.create(cluster.selfMember().address()));
      //#cluster-join

      cluster2.manager().tell(Join.create(cluster.selfMember().address()));

      // TODO wait for/verify cluster to form

      //#cluster-leave
      cluster2.manager().tell(Leave.create(cluster2.selfMember().address()));
      //#cluster-leave

      // TODO wait for/verify node 2 leaving

    } finally {
      system.terminate();
      system2.terminate();
    }
  }

  // @Test
  public void clusterLeave() throws Exception {
    ActorSystem<Object> system = ActorSystem.create(Behaviors.empty(), "ClusterSystem",
        BasicClusterManualSpec.noPort().withFallback(BasicClusterManualSpec.clusterConfig()));
    ActorSystem<Object> system2 = ActorSystem.create(Behaviors.empty(), "ClusterSystem",
        BasicClusterManualSpec.noPort().withFallback(BasicClusterManualSpec.clusterConfig()));

    try {
      Cluster cluster = Cluster.get(system);
      Cluster cluster2 = Cluster.get(system2);

      //#cluster-subscribe
      TestProbe<ClusterEvent.MemberEvent> testProbe = TestProbe.create(system);
      cluster.subscriptions().tell(Subscribe.create(testProbe.ref(), ClusterEvent.MemberEvent.class));
      //#cluster-subscribe

      //#cluster-leave-example
      cluster.manager().tell(Leave.create(cluster2.selfMember().address()));
      testProbe.expectMessageClass(ClusterEvent.MemberLeft.class);
      testProbe.expectMessageClass(ClusterEvent.MemberExited.class);
      testProbe.expectMessageClass(ClusterEvent.MemberRemoved.class);
      //#cluster-leave-example

    } finally {
      system.terminate();
      system2.terminate();
    }
  }
}
