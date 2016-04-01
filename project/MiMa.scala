/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

object MiMa extends AutoPlugin {

  override def requires = MimaPlugin
  override def trigger = allRequirements

  override val projectSettings = Seq(
    mimaBackwardIssueFilters ++= mimaIgnoredProblems,
    mimaPreviousArtifacts := akkaPreviousArtifacts(name.value, organization.value, scalaBinaryVersion.value)
  )

  def akkaPreviousArtifacts(projectName: String, organization: String, scalaBinaryVersion: String): Set[sbt.ModuleID] = {
    val versions = {
      val akka23Versions = Seq("2.3.11", "2.3.12", "2.3.13", "2.3.14")
      val akka24NoStreamVersions = Seq("2.4.0", "2.4.1")
      val akka24StreamVersions = Seq("2.4.2")
      val akka24NewArtifacts = Seq(
        "akka-cluster-sharding",
        "akka-cluster-tools",
        "akka-cluster-metrics",
        "akka-persistence",
        "akka-distributed-data-experimental",
        "akka-persistence-query-experimental"
      )
      val akka242NewArtifacts = Seq(
        "akka-stream",
        "akka-stream-testkit",
        "akka-http-core",
        "akka-http-experimental",
        "akka-http-testkit",
        "akka-http-jackson-experimental",
        "akka-http-spray-json-experimental",
        "akka-http-xml-experimental"
      )
      scalaBinaryVersion match {
        case "2.11" if !(akka24NewArtifacts ++ akka242NewArtifacts).contains(projectName) => akka23Versions ++ akka24NoStreamVersions ++ akka24StreamVersions
        case _ if akka242NewArtifacts.contains(projectName) => akka24StreamVersions
        case _ => akka24NoStreamVersions ++ akka24StreamVersions // Only Akka 2.4.x for scala > than 2.11
      }
    }

    // check against all binary compatible artifacts
    versions.map(organization %% projectName % _).toSet
  }

  case class FilterAnyProblem(name: String) extends com.typesafe.tools.mima.core.ProblemFilter {
    import com.typesafe.tools.mima.core._
    override def apply(p: Problem): Boolean = p match {
      case t: TemplateProblem => t.ref.fullName != name && t.ref.fullName != (name + '$')
      case m: MemberProblem => m.ref.owner.fullName != name && m.ref.owner.fullName != (name + '$')
    }
  }

  case class FilterAnyProblemStartingWith(start: String) extends com.typesafe.tools.mima.core.ProblemFilter {
    import com.typesafe.tools.mima.core._
    override def apply(p: Problem): Boolean = p match {
      case t: TemplateProblem => !t.ref.fullName.startsWith(start)
      case m: MemberProblem => !m.ref.owner.fullName.startsWith(start)
    }
  }

  val mimaIgnoredProblems = {
    import com.typesafe.tools.mima.core._

    val bcIssuesBetween23and24 = Seq(
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
      // Changes in akka-persistence in #13944
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
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.ThreadPoolConfig"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.UnboundedDequeBasedMailbox"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.CachingConfig$ValuePathEntry"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.MonitorableThreadFactory"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.ThreadPoolConfigBuilder"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.DefaultDispatcherPrerequisites"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.BoundedMailbox"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.UnboundedMailbox"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.SingleConsumerOnlyUnboundedMailbox"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.CachingConfig$StringPathEntry"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Supervise"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Recreate"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Resume"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Failed"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.DeathWatchNotification"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Create"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Suspend"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Unwatch"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Terminate"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.AddressTerminated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$Event"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.SuppressedDeadLetter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$LogEntry"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$CurrentState"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.StopChild"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.ContextualTypedActorFactory"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.Status$Failure"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$Transition"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$SubscribeTransitionCallBack"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.SelectChildPattern"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.SerializedActorRef"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.TypedActor$SerializedMethodCall"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.Status$Success"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$UnsubscribeTransitionCallBack"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.PostRestartException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$StopEvent"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.ActorKilledException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.ChildRestartStats"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.ActorNotFound"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.TypedProps"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.SchedulerException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.DeathPactException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$Timer"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.Identify"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.InvalidMessageException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.Terminated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.PreRestartException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.ActorIdentity"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.TypedActor$MethodCall"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.TypedActor$SerializedTypedActorInvocationHandler"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.IllegalActorStateException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.InvalidActorNameException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.SelectChildName"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$Failure"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.UnhandledMessage"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.DeadLetter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.FSM$TimeoutMarker"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.dungeon.ChildrenContainer$Recreation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.dungeon.ChildrenContainer$TerminatingChildrenContainer"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.dungeon.ChildrenContainer$Creation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.dsl.Inbox$Select"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.dsl.Inbox$StartWatch"),
      ProblemFilters.exclude[FinalClassProblem]("akka.actor.dsl.Inbox$Get"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$Received"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Udp$Send"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.TcpConnection$UpdatePendingWriteAndThen"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$Write"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$CommandFailed"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Udp$Bound"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.TcpConnection$ConnectionInfo"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$ErrorClosed"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.UdpConnected$Send"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.UdpConnected$Received"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Udp$CommandFailed"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.SelectionHandler$Retry"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$WriteFile"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$Bound"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.UdpConnected$CommandFailed"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$Register"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$ResumeAccepting"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.SelectionHandler$WorkerForCommand"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.TcpConnection$CloseInformation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.TcpConnection$WriteFileFailed"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.TcpListener$RegisterIncoming"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$Connect"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$Bind"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Udp$Received"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$Connected"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.UdpConnected$Connect"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Udp$Bind"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.Tcp$CompoundWrite"),
      ProblemFilters.exclude[FinalClassProblem]("akka.io.TcpListener$FailedRegisterIncoming"),
      ProblemFilters.exclude[FinalClassProblem]("akka.event.Logging$InitializeLogger"),
      ProblemFilters.exclude[FinalClassProblem]("akka.pattern.PromiseActorRef$StoppedWithPath"),
      ProblemFilters.exclude[FinalClassProblem]("akka.serialization.Serialization$Information"),
      ProblemFilters.exclude[FinalClassProblem]("akka.util.WildcardTree"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.AddRoutee"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.ConsistentRoutee"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.SeveralRoutees"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.ScatterGatherFirstCompletedRoutees"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.Deafen"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.Listen"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.AdjustPoolSize"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.ActorSelectionRoutee"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.Broadcast"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.RemoveRoutee"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.ActorRefRoutee"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.Routees"),
      ProblemFilters.exclude[FinalClassProblem]("akka.routing.WithListeners"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.TestEvent$Mute"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.TestActor$UnWatch"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.ErrorFilter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.InfoFilter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.TestActor$Watch"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.WarningFilter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.DebugFilter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.DeadLettersFilter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.TestActor$RealMessage"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.TestEvent$UnMute"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.TestActor$SetIgnore"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.TestActor$SetAutoPilot"),
      ProblemFilters.exclude[FinalClassProblem]("akka.testkit.CustomEventFilter"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$ListensFailure"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.InvalidAssociation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteScope"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.AckedReceiveBuffer"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteWatcher$WatchRemote"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$Gated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.AckedSendBuffer"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$Link"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.QuarantinedEvent"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$ManagementCommand"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$Send"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.Ack"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteWatcher$Stats"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$Quarantined"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RARP"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$ResendState"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteWatcher$RewatchRemote"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.Remoting$RegisterTransportActor"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.ShutDownAssociation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$Quarantine"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteWatcher$UnwatchRemote"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointWriter$Handle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteActorRefProvider$Internals"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$ListensResult"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteWatcher$ExpectedFirstHeartbeat"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteDeploymentWatcher$WatchRemote"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.DaemonMsgCreate"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.RemoteWatcher$HeartbeatRsp"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointWriter$StoppedReading"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$Listen"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointWriter$OutboundAck"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.HeartbeatHistory"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.HopelessAssociation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointWriter$TookOver"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.SeqNo"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.ReliableDeliverySupervisor$GotUid"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.EndpointManager$ManagementCommandAck"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.FailureInjectorTransportAdapter$Drop"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerManager$AssociateResult"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerManager$Listener"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ProtocolStateActor$OutboundUnassociated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ProtocolStateActor$Handle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerTransportAdapter$SetThrottle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ProtocolStateActor$ListenerReady"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ActorTransportAdapter$ListenUnderlying"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ActorTransportAdapter$AssociateUnderlying"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.FailureInjectorTransportAdapter$One"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.TestTransport$DisassociateAttempt"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ProtocolStateActor$HandleListenerRegistered"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ProtocolStateActor$OutboundUnderlyingAssociated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ProtocolStateActor$InboundUnassociated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AkkaProtocolTransport$AssociateUnderlyingRefuseUid"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.TestTransport$AssociateAttempt"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.TestTransport$ListenAttempt"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AkkaPduCodec$Disassociate"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AkkaPduCodec$Message"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.Transport$InboundAssociation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.Transport$InvalidAssociationException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ProtocolStateActor$AssociatedWaitHandler"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.TestAssociationHandle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerManager$ListenerAndMode"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AssociationHandle$Disassociated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AssociationHandle$InboundPayload"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottledAssociation$FailWith"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.Transport$ActorAssociationEventListener"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.FailureInjectorHandle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AkkaPduCodec$Payload"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottledAssociation$ExposedHandle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.FailureInjectorTransportAdapter$All"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.TestTransport$WriteAttempt"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerManager$Handle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerTransportAdapter$TokenBucket"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerManager$Checkin"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.HandshakeInfo"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerTransportAdapter$ForceDisassociate"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ActorTransportAdapter$DisassociateUnderlying"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.FailureInjectorException"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerHandle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.TestTransport$ShutdownAttempt"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ActorTransportAdapter$ListenerRegistered"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AkkaPduCodec$Associate"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.ThrottlerTransportAdapter$ForceDisassociateExplicitly"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.transport.AssociationHandle$ActorHandleEventListener"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.FailureResult"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.MessageResult"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.internal.CamelSupervisor$AddWatch"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.internal.AwaitDeActivation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.internal.CamelSupervisor$CamelProducerObjects"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.internal.CamelSupervisor$DeRegister"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.internal.AwaitActivation"),
      ProblemFilters.exclude[FinalClassProblem]("akka.camel.internal.CamelSupervisor$Register"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.ClientFSM$Data"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.ToClient"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$ClientLost"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.EnterBarrier"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.Remove"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.ToServer"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.DisconnectMsg"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$BarrierEmpty"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.Disconnect"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.ClientFSM$ConnectionFailure"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.RoleName"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.Hello"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.Controller$NodeInfo"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.Controller$CreateServerFSM"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.GetAddress"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$WrongBarrier"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.AddressReply"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.ClientFSM$Connected"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$RemoveClient"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$FailedBarrier"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.ThrottleMsg"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$Data"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierResult"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$BarrierTimeout"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.Controller$ClientDisconnected"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.FailBarrier"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.BarrierCoordinator$DuplicateNode"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testconductor.Throttle"),
      ProblemFilters.exclude[FinalClassProblem]("akka.remote.testkit.MultiNodeSpec$Replacement"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$Subscribe"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$SeenChanged"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.VectorClock"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$PublishChanges"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.Metric"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$ReachableMember"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterUserAction$Down"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterHeartbeatSender$ExpectedFirstHeartbeat"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$PublishEvent"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$RoleLeaderChanged"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterHeartbeatSender$HeartbeatRsp"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$ClusterMetricsChanged"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.AutoDown$UnreachableTimeout"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$CurrentInternalStats"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$MemberUp"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$CurrentClusterState"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.GossipOverview"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.GossipStatus"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.GossipStats"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.MetricsGossip"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$Join"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.UniqueAddress"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$SendGossipTo"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$PublisherCreated"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterUserAction$Leave"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.Gossip"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$ReachabilityChanged"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$UnreachableMember"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$AddOnMemberUpListener"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$LeaderChanged"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$JoinSeedNodes"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.StandardMetrics$HeapMemory"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.VectorClockStats"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.NodeMetrics"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.Reachability$Record"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$InitJoinAck"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.StandardMetrics$Cpu"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$InitJoinNack"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.EWMA"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$Unsubscribe"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterHeartbeatSender$Heartbeat"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.MetricsGossipEnvelope"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$MemberRemoved"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterUserAction$JoinTo"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.ClusterEvent$MemberExited"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.InternalClusterAction$Welcome"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.routing.ClusterRouterPoolSettings"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.routing.MixMetricsSelector"),
      ProblemFilters.exclude[FinalClassProblem]("akka.cluster.routing.ClusterRouterGroupSettings"),
      ProblemFilters.exclude[FinalClassProblem]("akka.dispatch.sysmsg.Watch"),

      // changed to static method, source compatible is enough
      ProblemFilters.exclude[MissingMethodProblem]("akka.testkit.JavaTestKit.shutdownActorSystem"),
      // testActorName()java.lang.String in trait akka.testkit.TestKitBase does not have a correspondent in old version
      ProblemFilters.exclude[MissingMethodProblem]("akka.testkit.TestKitBase.testActorName"),
      // method remainingOrDefault()scala.concurrent.duration.FiniteDuration in trait akka.testkit.TestKitBase does not have a correspondent in old version
      ProblemFilters.exclude[MissingMethodProblem]("akka.testkit.TestKitBase.remainingOrDefault"),
      // synthetic method akka$remote$testkit$MultiNodeSpec$Replacement$$$outer()akka.remote.testkit.MultiNodeSpec in class akka.remote.testkit.MultiNodeSpec#Replacement does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.testkit.MultiNodeSpec#Replacement.akka$remote$testkit$MultiNodeSpec$Replacement$$$outer"),


      // method nrOfInstances(akka.actor.ActorSystem) in trait akka.routing.Pool does not have a correspondent in old version
      // ok to exclude, since we don't call nrOfInstances(sys) for old implementations
      ProblemFilters.exclude[MissingMethodProblem]("akka.routing.Pool.nrOfInstances"),

      // method paths(akka.actor.ActorSystem) in trait akka.routing.Group does not have a correspondent in old version
      // ok to exclude, since we don't call paths(sys) for old implementations
      ProblemFilters.exclude[MissingMethodProblem]("akka.routing.Group.paths"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.routing.GroupBase.getPaths"),

      // removed deprecated
      ProblemFilters.exclude[MissingClassProblem]("akka.actor.UntypedActorFactory"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.util.Timeout.longToTimeout"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.util.Timeout.intToTimeout"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.util.Timeout.apply"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.util.Timeout.this"),
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
      ProblemFilters.exclude[MissingMethodProblem]("akka.testkit.TestKit.dilated"),


      // changed internals
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ActorSystem.terminate"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ActorSystem.whenTerminated"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ExtendedActorSystem.logFilter"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ActorPath.ValidSymbols"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.LocalActorRefProvider.terminationPromise"),
      ProblemFilters.exclude[MissingClassProblem]("akka.actor.UntypedActorFactoryConsumer"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ActorSystemImpl.terminationFuture"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.IndirectActorProducer.UntypedActorFactoryConsumerClass"),
      FilterAnyProblem("akka.actor.ActorSystemImpl"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.AskSupport.ask"),
      FilterAnyProblem("akka.actor.ActorSystemImpl$TerminationCallbacks"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.event.Logging#LogEvent.getMDC"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.util.ByteString.byteStringCompanion"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.util.ByteString.writeToOutputStream"),
      //method boss()akka.actor.RepointableActorRef in class akka.actor.ActorDSL#Extension does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ActorDSL#Extension.boss"),
      // method hasSubscriptions(java.lang.Object)Boolean in trait akka.event.SubchannelClassification does not have a correspondent in old version
      // ok to exclude since it is only invoked from new EventStreamUnsubscriber
      ProblemFilters.exclude[MissingMethodProblem]("akka.event.SubchannelClassification.hasSubscriptions"),
      FilterAnyProblem("akka.remote.EndpointManager"),
      FilterAnyProblem("akka.remote.RemoteTransport"),
      FilterAnyProblem("akka.remote.Remoting"),
      FilterAnyProblem("akka.remote.PhiAccrualFailureDetector$State"),
      FilterAnyProblem("akka.cluster.ClusterDomainEventPublisher"),
      FilterAnyProblem("akka.cluster.InternalClusterAction"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.InternalClusterAction$PublishCurrentClusterState"),
      // issue #16327 compared to 2.3.10
      // synthetic method akka$dispatch$BatchingExecutor$BlockableBatch$$parentBlockContext_=(scala.concurrent.BlockContext)Unit in class akka.dispatch.BatchingExecutor#BlockableBatch does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor#BlockableBatch.akka$dispatch$BatchingExecutor$BlockableBatch$$parentBlockContext_="),
      // synthetic method akka$dispatch$BatchingExecutor$_setter_$akka$dispatch$BatchingExecutor$$_blockContext_=(java.lang.ThreadLocal)Unit in trait akka.dispatch.BatchingExecutor does not have a correspondent in old version
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor.akka$dispatch$BatchingExecutor$_setter_$akka$dispatch$BatchingExecutor$$_blockContext_="),
      // synthetic method akka$dispatch$BatchingExecutor$$_blockContext()java.lang.ThreadLocal in trait akka.dispatch.BatchingExecutor does not have a correspondent in old version
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.BatchingExecutor.akka$dispatch$BatchingExecutor$$_blockContext"),
      // issue #16327 compared to 2.3.11
      // synthetic method akka$dispatch$BatchingExecutor$_setter_$akka$dispatch$BatchingExecutor$$_blockContext_=(java.lang.ThreadLocal)Unit in class akka.dispatch.MessageDispatcher does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.MessageDispatcher.akka$dispatch$BatchingExecutor$_setter_$akka$dispatch$BatchingExecutor$$_blockContext_="),
      // synthetic method akka$dispatch$BatchingExecutor$$_blockContext()java.lang.ThreadLocal in class akka.dispatch.MessageDispatcher does not have a correspondent in new version
      ProblemFilters.exclude[MissingMethodProblem]("akka.dispatch.MessageDispatcher.akka$dispatch$BatchingExecutor$$_blockContext"),
      // issue #16736
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.OnMemberUpListener"),
      // issue #17554
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.maxResendRate"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.resendLimit"),
      //changes introduced by #16911
      ProblemFilters.exclude[MissingMethodProblem]("akka.remote.RemoteActorRefProvider.afterSendSystemMessage"),
      FilterAnyProblem("akka.remote.RemoteWatcher"),
      FilterAnyProblem("akka.remote.RemoteWatcher$WatchRemote"),
      FilterAnyProblem("akka.remote.RemoteWatcher$UnwatchRemote"),
      FilterAnyProblem("akka.remote.RemoteWatcher$Rewatch"),
      FilterAnyProblem("akka.remote.RemoteWatcher$RewatchRemote"),
      FilterAnyProblem("akka.remote.RemoteWatcher$Stats"),
      // internal changes introduced by #17253
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.ClusterDaemon.coreSupervisor"),
      ProblemFilters.exclude[MissingMethodProblem]("akka.cluster.ClusterCoreSupervisor.publisher"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.ClusterCoreSupervisor.coreDaemon"),
      // protofbuf embedding #13783
      FilterAnyProblemStartingWith("akka.remote.WireFormats"),
      FilterAnyProblemStartingWith("akka.remote.ContainerFormats"),
      FilterAnyProblemStartingWith("akka.remote.serialization.DaemonMsgCreateSerializer"),
      FilterAnyProblemStartingWith("akka.remote.testconductor.TestConductorProtocol"),
      FilterAnyProblemStartingWith("akka.cluster.protobuf.msg.ClusterMessages"),
      FilterAnyProblemStartingWith("akka.cluster.protobuf.ClusterMessageSerializer"),
      // #13584 change in internal actor
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.ClusterCoreDaemon.akka$cluster$ClusterCoreDaemon$$isJoiningToUp$1")
    )

    Map(
      "2.3.11" -> Seq(
        ProblemFilters.exclude[MissingMethodProblem]("akka.actor.ActorCell.clearActorFields") // #17805, incompatibility with 2.4.x fixed in 2.3.12
      ),
      "2.3.14" -> bcIssuesBetween23and24,
      "2.4.0" -> Seq(
        FilterAnyProblem("akka.remote.transport.ProtocolStateActor"),

        //#18353 Changes to methods and fields private to remoting actors
        ProblemFilters.exclude[MissingMethodProblem]("akka.remote.EndpointManager.retryGateEnabled"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.EndpointManager.pruneTimerCancellable"),

        // #18722 internal changes to actor
        FilterAnyProblem("akka.cluster.sharding.DDataShardCoordinator"),

        // #18328 optimize VersionVector for size 1
        FilterAnyProblem("akka.cluster.ddata.VersionVector")
      ),
      "2.4.1" -> Seq(
        // #19008
        FilterAnyProblem("akka.persistence.journal.inmem.InmemJournal"),
        FilterAnyProblem("akka.persistence.journal.inmem.InmemStore"),

        // #19133 change in internal actor
        ProblemFilters.exclude[MissingMethodProblem]("akka.remote.ReliableDeliverySupervisor.gated"),

        // #18758 report invalid association events
        ProblemFilters.exclude[MissingTypesProblem]("akka.remote.InvalidAssociation$"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.remote.InvalidAssociation.apply"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.remote.InvalidAssociation.copy"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.remote.InvalidAssociation.this"),

        // #19281 BackoffSupervisor updates
        ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.BackoffSupervisor.akka$pattern$BackoffSupervisor$$child_="),
        ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.BackoffSupervisor.akka$pattern$BackoffSupervisor$$restartCount"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.BackoffSupervisor.akka$pattern$BackoffSupervisor$$restartCount_="),
        ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.BackoffSupervisor.akka$pattern$BackoffSupervisor$$child"),

        // #19487
        FilterAnyProblem("akka.actor.dungeon.Children"),

        // #19440
        ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.PipeToSupport.pipeCompletionStage"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.FutureTimeoutSupport.afterCompletionStage")
      ),
      "2.4.2" -> Seq(
        //internal API
        FilterAnyProblemStartingWith("akka.http.impl"),
        FilterAnyProblemStartingWith("akka.stream.impl"),

        ProblemFilters.exclude[FinalClassProblem]("akka.stream.stage.GraphStageLogic$Reading"), // this class is private

        // lifting this method to the type where it belongs
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOpsMat.mapMaterializedValue"),

        // #19815 make HTTP compile under Scala 2.12.0-M3
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.http.scaladsl.model.headers.CacheDirectives#private.apply"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.http.scaladsl.model.headers.CacheDirectives#no-cache.apply"),

        // #19983 withoutSizeLimit overrides for Scala API
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.RequestEntity.withoutSizeLimit"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.UniversalEntity.withoutSizeLimit"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.ResponseEntity.withoutSizeLimit"),

        // #19162 javadsl initialization issues and model cleanup
        ProblemFilters.exclude[FinalClassProblem]("akka.http.javadsl.model.MediaTypes"),

        // #19956 Remove exposed case classes in HTTP model
        ProblemFilters.exclude[MissingTypesProblem]("akka.http.scaladsl.model.HttpRequest$"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.scaladsl.model.HttpRequest.unapply"), // returned Option[HttpRequest], now returns HttpRequest – no Option allocations!
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.<init>$default$1"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.<init>$default$2"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.<init>$default$3"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.<init>$default$4"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.<init>$default$5"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.http.scaladsl.model.HttpResponse"), // was a case class (Serializable, Product, Equals)
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.productElement"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.productArity"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.canEqual"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.productIterator"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.productPrefix"),

        ProblemFilters.exclude[MissingTypesProblem]("akka.http.scaladsl.model.HttpRequest"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.productElement"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.productArity"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.canEqual"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.productIterator"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpRequest.productPrefix"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.http.scaladsl.model.HttpResponse$"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.scaladsl.model.HttpResponse.unapply"), // returned Option[HttpRequest], now returns HttpRequest – no Option allocations!
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.<init>$default$1"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.<init>$default$2"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.<init>$default$3"),
        ProblemFilters.exclude[MissingMethodProblem]("akka.http.scaladsl.model.HttpResponse.<init>$default$4"),

        // #19162 fixing javadsl initialization edge-cases
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.javadsl.model.ContentTypes.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.javadsl.model.MediaTypes.this"),

        // #20014 should have been final always
        ProblemFilters.exclude[FinalClassProblem]("akka.http.scaladsl.model.EntityStreamSizeException"),

        // #19849 content negotiation fixes
        ProblemFilters.exclude[FinalClassProblem]("akka.http.scaladsl.marshalling.Marshal$UnacceptableResponseContentTypeException"),

        // #15947 catch mailbox creation failures
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.RepointableActorRef.point"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.Dispatch.initWithFailure"),

        // #19828
        ProblemFilters.exclude[DirectAbstractMethodProblem]("akka.persistence.Eventsourced#ProcessingState.onWriteMessageComplete"),
        ProblemFilters.exclude[ReversedAbstractMethodProblem]("akka.persistence.Eventsourced#ProcessingState.onWriteMessageComplete"),

        // #19390 Add flow monitor
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOpsMat.monitor")
      )
    )
  }
}
