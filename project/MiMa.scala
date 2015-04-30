/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaKeys.binaryIssueFilters
import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings

object MiMa extends AutoPlugin {

  override def trigger = allRequirements

  override val projectSettings = mimaDefaultSettings ++ Seq(
    previousArtifact := None,
    binaryIssueFilters ++= mimaIgnoredProblems
  )
  
  case class FilterAnyProblem(name: String) extends com.typesafe.tools.mima.core.ProblemFilter {
    import com.typesafe.tools.mima.core._
    override def apply(p: Problem): Boolean = p match {
      case t: TemplateProblem => t.ref.fullName != name && t.ref.fullName != (name + '$')
      case m: MemberProblem => m.ref.owner.fullName != name && m.ref.owner.fullName != (name + '$')
    }
  }
  
  case object IgnoreFinalClassProblem extends com.typesafe.tools.mima.core.ProblemFilter {
    import com.typesafe.tools.mima.core._
    override def apply(p: Problem): Boolean = p match {
      case _: FinalClassProblem => false
      case _ => true
    }
  }
  

  val mimaIgnoredProblems = {
      import com.typesafe.tools.mima.core._
    Seq(
      FilterAnyProblem("akka.remote.testconductor.Terminate"),
      FilterAnyProblem("akka.remote.testconductor.TerminateMsg"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.testconductor.Conductor.shutdown"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.testkit.MultiNodeSpec.akka$remote$testkit$MultiNodeSpec$$deployer"),
      FilterAnyProblem("akka.remote.EndpointManager$Pass"),
      FilterAnyProblem("akka.remote.EndpointManager$EndpointRegistry"),
      FilterAnyProblem("akka.remote.EndpointWriter"),
      FilterAnyProblem("akka.remote.EndpointWriter$StopReading"),
      FilterAnyProblem("akka.remote.EndpointWriter$State"),
      FilterAnyProblem("akka.remote.EndpointWriter$TakeOver"),

      // Change of internal message by #15109
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor#GotUid.copy"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor#GotUid.this"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.remote.ReliableDeliverySupervisor$GotUid$"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor#GotUid.apply"),

      // Change of private method to protected by #15212
      ProblemFilters.exclude[MissingMethodProblem]("akka.persistence.snapshot.local.LocalSnapshotStore.akka$persistence$snapshot$local$LocalSnapshotStore$$save"),

      // Changes in akka-stream-experimental are not binary compatible - still source compatible (2.3.3 -> 2.3.4)
      // Adding `PersistentActor.persistAsync`
      // Adding `PersistentActor.defer`
      // Changes in akka-persistence-experimental in #13944
      // Changes in private LevelDB Store by #13962
      // Renamed `processorId` to `persistenceId`
      ProblemFilters.excludePackage("akka.persistence"),

      // Adding wildcardFanOut to internal message ActorSelectionMessage by #13992
      FilterAnyProblem("akka.actor.ActorSelectionMessage$"),
      FilterAnyProblem("akka.actor.ActorSelectionMessage"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ContainerFormats#SelectionEnvelopeOrBuilder.hasWildcardFanOut"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ContainerFormats#SelectionEnvelopeOrBuilder.getWildcardFanOut"),

      // Adding expectMsg overload to testkit #15425
      ProblemFilters.exclude[MissingMethodProblem]("akka.testkit.TestKitBase.expectMsg"),

      // Adding akka.japi.Option.getOrElse #15383
      ProblemFilters.exclude[MissingMethodProblem]("akka.japi.Option.getOrElse"),

      // Change to internal API to fix #15991
      ProblemFilters.exclude[MissingClassProblem]("akka.io.TcpConnection$UpdatePendingWrite$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.io.TcpConnection$UpdatePendingWrite"),

      // Change to optimize use of ForkJoin with Akka's Mailbox
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.Mailbox.status"),

      // Changes introduced to internal remoting actors by #16623
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.unstashAcks"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.pendingAcks_="),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.pendingAcks"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.scheduleAutoResend"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.autoResendTimer_="),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.rescheduleAutoResend"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.ReliableDeliverySupervisor.autoResendTimer"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.lastCumulativeAck"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.ReliableDeliverySupervisor.bailoutAt"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.lastCumulativeAck_="),
      
      // Change to improve cluster heartbeat sender, #16638
      FilterAnyProblem("akka.cluster.HeartbeatNodeRing"),
      FilterAnyProblem("akka.cluster.ClusterHeartbeatSenderState"),

      //Changes to improve BatchingExecutor, bugfix #16327
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor.resubmitOnBlock"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.BatchingExecutor$Batch"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor#Batch.initial"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor#Batch.blockOn"),
      ProblemFilters.exclude[FinalMethodProblem]("akka.dispatch.BatchingExecutor#Batch.run"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor#Batch.akka$dispatch$BatchingExecutor$Batch$$parentBlockContext_="),
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor#Batch.this"),
      
      // Exclude observations from downed, #13875
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.ClusterEvent.diffReachable"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.ClusterEvent.diffSeen"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.ClusterEvent.diffUnreachable"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.ClusterEvent.diffRolesLeader"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.ClusterEvent.diffLeader"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.Gossip.convergence"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.Gossip.akka$cluster$Gossip$$convergenceMemberStatus"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.Gossip.isLeader"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.Gossip.leader"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.Gossip.roleLeader"),
      
      // copied everything above from release-2.3 branch
      
      // final case classes
      IgnoreFinalClassProblem, 
      
      
      // removed deprecated
      ProblemFilters.exclude[MissingClassProblem]("akka.actor.UntypedActorFactory"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.util.Timeout.longToTimeout"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.util.Timeout.intToTimeout"),
      FilterAnyProblem("akka.routing.ConsistentHashingRouter"),
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.SmallestMailboxRouter$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.RouterRoutees$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.ScatterGatherFirstCompletedRouter"),
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.CurrentRoutees$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.CurrentRoutees"),
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.RouterRoutees"),
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.RandomRouter"),
      // class akka.routing.CollectRouteeRefs does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.CollectRouteeRefs"),
      // method nrOfInstances()Int in trait akka.routing.Pool does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.routing.Pool.nrOfInstances"),
      // method nrOfInstances(akka.actor.ActorSystem)Int in trait akka.routing.Pool does not have a correspondent in old version
      ProblemFilters.exclude[MissingMethodProblem]("akka.routing.Pool.nrOfInstances"),
      // class akka.routing.ConsistentActorRef does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.ConsistentActorRef"),
      // object akka.routing.ConsistentActorRef does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.ConsistentActorRef$"),
      // object akka.routing.RandomRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.RandomRouter$"),
      // object akka.routing.BroadcastRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.BroadcastRouter$"),
      // class akka.routing.RoundRobinRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.RoundRobinRouter"),
      // class akka.routing.BroadcastRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.BroadcastRouter"),
      // class akka.routing.SmallestMailboxRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.SmallestMailboxRouter"),
      // object akka.routing.ScatterGatherFirstCompletedRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.ScatterGatherFirstCompletedRouter$"),
      // interface akka.routing.DeprecatedRouterConfig does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.DeprecatedRouterConfig"),
      // object akka.routing.RoundRobinRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.routing.RoundRobinRouter$"),
      // method nrOfInstances()Int in class akka.routing.FromConfig does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.routing.FromConfig.nrOfInstances"),
      // method toString()java.lang.String in object akka.routing.BalancingPool does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.routing.BalancingPool.toString"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.RemoteSettings.LogRemoteLifecycleEvents"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.Cluster.publishCurrentClusterState"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.InternalClusterAction$PublishCurrentClusterState$"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.ClusterSettings.AutoDown"),
      // class akka.cluster.routing.ClusterRouterSettings does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.ClusterRouterSettings"),
      // object akka.cluster.routing.ClusterRouterConfig does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.ClusterRouterConfig$"),
      // method nrOfInstances()Int in class akka.cluster.routing.ClusterRouterPool does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.routing.ClusterRouterPool.nrOfInstances"),
      // object akka.cluster.routing.AdaptiveLoadBalancingRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingRouter$"),
      // object akka.cluster.routing.ClusterRouterSettings does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.ClusterRouterSettings$"),
      // class akka.cluster.routing.AdaptiveLoadBalancingRouter does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingRouter"),
      // class akka.cluster.routing.ClusterRouterConfig does not have a correspondent in new version
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.ClusterRouterConfig"),
      // deprecated method this(Int,java.lang.String,Boolean,java.lang.String)Unit in class akka.cluster.routing.ClusterRouterGroupSettings does not have a correspondent with same parameter signature among (Int,java.lang.Iterable,Boolean,java.lang.String)Unit, (Int,scala.collection.immutable.Seq,Boolean,scala.Option)Unit
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.routing.ClusterRouterGroupSettings.this"),
      // deprecated method this(Int,java.lang.String,Boolean,scala.Option)Unit in class akka.cluster.routing.ClusterRouterGroupSettings does not have a correspondent with same parameter signature among (Int,java.lang.Iterable,Boolean,java.lang.String)Unit, (Int,scala.collection.immutable.Seq,Boolean,scala.Option)Unit
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.routing.ClusterRouterGroupSettings.this"),
      
      
      // changed internals
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.LocalActorRefProvider.terminationPromise"),
      ProblemFilters.exclude[MissingClassProblem]("akka.actor.UntypedActorFactoryConsumer"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.FSM#State.copy"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ActorSystemImpl.terminationFuture"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.IndirectActorProducer.UntypedActorFactoryConsumerClass"),
      FilterAnyProblem("akka.actor.ActorSystemImpl"),
      FilterAnyProblem("akka.pattern.PromiseActorRef"),
      FilterAnyProblem("akka.actor.ActorSystemImpl$TerminationCallbacks"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.event.Logging#LogEvent.getMDC"),
      FilterAnyProblem("akka.remote.EndpointManager"),
      FilterAnyProblem("akka.remote.RemoteTransport"),
      FilterAnyProblem("akka.remote.Remoting"),
      FilterAnyProblem("akka.remote.PhiAccrualFailureDetector$State"),
      FilterAnyProblem("akka.cluster.ClusterDomainEventPublisher"),
      FilterAnyProblem("akka.cluster.InternalClusterAction"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.InternalClusterAction$PublishCurrentClusterState")
      
     )
  }
}
