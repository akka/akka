/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import com.typesafe.tools.mima.core.ProblemFilters
import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

import scala.util.Try

object MiMa extends AutoPlugin {

  override def requires = MimaPlugin
  override def trigger = allRequirements

  override val projectSettings = Seq(
    mimaBackwardIssueFilters ++= mimaIgnoredProblems,
    mimaPreviousArtifacts := akkaPreviousArtifacts(name.value, organization.value, scalaBinaryVersion.value)
  )

  def akkaPreviousArtifacts(projectName: String, organization: String, scalaBinaryVersion: String): Set[sbt.ModuleID] = {
    val versions: Seq[String] = {
      def latestMinorVersionOf(major: String) = mimaIgnoredProblems.keys
        .map(_.stripPrefix(major))
        .map(minor => scala.util.Try(minor.toInt))
        .collect {
          case scala.util.Success(m) => m
        }
        .max

      val akka24NoStreamVersions = Seq("2.4.0", "2.4.1")
      val akka25Versions = (0 to latestMinorVersionOf("2.5.")).map(patch => s"2.5.$patch")
      val akka24StreamVersions = (2 to 12) map ("2.4." + _)
      val akka24WithScala212 =
        (13 to latestMinorVersionOf("2.4."))
          .map ("2.4." + _)
          .filterNot(_ == "2.4.15") // 2.4.15 was released from the wrong branch and never announced

      val akka242NewArtifacts = Seq(
        "akka-stream",
        "akka-http-core",
        "akka-http-testkit",
        "akka-stream-testkit"
      )
      val akka250NewArtifacts = Seq(
        "akka-persistence-query"
      )

      scalaBinaryVersion match {
        case "2.11" =>
          if (akka250NewArtifacts.contains(projectName)) akka25Versions
          else {
            if (!akka242NewArtifacts.contains(projectName)) akka24NoStreamVersions
            else Seq.empty
          } ++ akka24StreamVersions ++ akka24WithScala212 ++ akka25Versions

        case "2.12" =>
          akka24WithScala212 ++ akka25Versions
      }
    }

    val akka25PromotedArtifacts = Set(
      "akka-distributed-data"
    )

    // check against all binary compatible artifacts
    versions.map { v =>
      val adjustedProjectName =
        if (akka25PromotedArtifacts(projectName) && v.startsWith("2.4"))
          projectName + "-experimental"
        else
          projectName
      organization %% adjustedProjectName % v
    }.toSet
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

  def mimaIgnoredProblems = {
    import com.typesafe.tools.mima.core._

    val bcIssuesBetween24and25 = Seq(
      // ##22269 GSet as delta-CRDT
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.GSet.this"), // constructor supplied by companion object

      // # 18262 embed FJP, Mailbox extends ForkJoinTask
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.dispatch.ForkJoinExecutorConfigurator#ForkJoinExecutorServiceFactory.threadFactory"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.dispatch.ForkJoinExecutorConfigurator#ForkJoinExecutorServiceFactory.this"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.dispatch.ForkJoinExecutorConfigurator#ForkJoinExecutorServiceFactory.this"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.dispatch.ForkJoinExecutorConfigurator.validate"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.dispatch.MonitorableThreadFactory"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.dispatch.MonitorableThreadFactory.newThread"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinPool"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.dispatch.ForkJoinExecutorConfigurator#AkkaForkJoinPool.this"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.dispatch.ForkJoinExecutorConfigurator#AkkaForkJoinPool.this"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.dispatch.Mailbox"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.dispatch.BalancingDispatcher$SharingMailbox"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.dispatch.MonitorableThreadFactory$AkkaForkJoinWorkerThread"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.dispatch.MonitorableThreadFactory#AkkaForkJoinWorkerThread.this"),

      // #21875 delta-CRDT
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.GCounter.this"),

      // #22188 ORSet delta-CRDT
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.ORSet.this"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.protobuf.SerializationSupport.versionVectorToProto"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.protobuf.SerializationSupport.versionVectorFromProto"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.protobuf.SerializationSupport.versionVectorFromBinary"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.ddata.protobuf.ReplicatedDataSerializer.versionVectorToProto"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.ddata.protobuf.ReplicatedDataSerializer.versionVectorFromProto"),

      // #22141 sharding minCap
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.updatingStateTimeout"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.waitingForStateTimeout"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.this"),

      // #22295 Improve Circuit breaker
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.pattern.CircuitBreaker#State.callThrough"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.pattern.CircuitBreaker#State.invoke"),

      // #21423 Remove deprecated metrics
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ClusterReadView.clusterMetrics"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.InternalClusterAction$MetricsTick$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.MetricsCollector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.Metric"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.MetricsCollector$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.Metric$"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ClusterSettings.MetricsMovingAverageHalfLife"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ClusterSettings.MetricsGossipInterval"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ClusterSettings.MetricsCollectorClass"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ClusterSettings.MetricsInterval"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ClusterSettings.MetricsEnabled"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.JmxMetricsCollector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.SigarMetricsCollector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.StandardMetrics$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.MetricNumericConverter"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.ClusterEvent$ClusterMetricsChanged"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.MetricsGossipEnvelope"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.StandardMetrics"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.NodeMetrics"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.StandardMetrics$Cpu$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.StandardMetrics$Cpu"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.InternalClusterAction$PublisherCreated"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.EWMA"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.MetricsGossip$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.InternalClusterAction$PublisherCreated$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.NodeMetrics$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.MetricsGossipEnvelope$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.ClusterMetricsCollector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.EWMA$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.StandardMetrics$HeapMemory"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.MetricsGossip"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.ClusterEvent$ClusterMetricsChanged$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.StandardMetrics$HeapMemory$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.SystemLoadAverageMetricsSelector$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingMetricsListener"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.WeightedRoutees"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingPool"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.CpuMetricsSelector$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.MixMetricsSelector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.CapacityMetricsSelector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.SystemLoadAverageMetricsSelector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingRoutingLogic"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.HeapMetricsSelector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingPool$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.CpuMetricsSelector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingRoutingLogic$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.HeapMetricsSelector$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.MetricsSelector$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingGroup$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.MixMetricsSelectorBase"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.AdaptiveLoadBalancingGroup"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.MixMetricsSelector$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.routing.MetricsSelector"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$EWMA$Builder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$MetricOrBuilder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$Number"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$NumberType"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$MetricsGossipEnvelopeOrBuilder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$Builder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetricsOrBuilder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$NumberOrBuilder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$EWMA"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$MetricsGossip$Builder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$MetricsGossipOrBuilder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$MetricsGossipEnvelope"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$MetricsGossip"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$MetricsGossipEnvelope$Builder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$EWMAOrBuilder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$Metric"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$Metric$Builder"),
      ProblemFilters.exclude[MissingClassProblem]("akka.cluster.protobuf.msg.ClusterMessages$NodeMetrics$Number$Builder"),

      // #22154 Sharding remembering entities with ddata, internal actors
      FilterAnyProblemStartingWith("akka.cluster.sharding.Shard"),
      FilterAnyProblemStartingWith("akka.cluster.sharding.PersistentShard"),
      FilterAnyProblemStartingWith("akka.cluster.sharding.ClusterShardingGuardian"),
      FilterAnyProblemStartingWith("akka.cluster.sharding.ShardRegion"),

      // #21647 pruning
      FilterAnyProblemStartingWith("akka.cluster.ddata.PruningState"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.RemovedNodePruning.modifiedByNodes"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.Replicator"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.protobuf.msg"),

      // #21647 pruning
      FilterAnyProblemStartingWith("akka.cluster.ddata.PruningState"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.RemovedNodePruning.usingNodes"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.Replicator"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.protobuf.msg"),

      // #21537 coordinated shutdown
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ClusterCoreDaemon.removed"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.Gossip.convergence"),

      //#21717 Improvements to AbstractActor API
      FilterAnyProblemStartingWith("akka.japi.pf.ReceiveBuilder"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.AbstractActor.receive"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.AbstractActor.createReceive"),
      ProblemFilters.exclude[MissingClassProblem]("akka.actor.AbstractActorContext"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.actor.AbstractActor.getContext"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.actor.AbstractActor.emptyBehavior"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.Children.findChild"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.actor.ActorCell"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.routing.RoutedActorCell"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.routing.ResizablePoolCell"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.AbstractPersistentActor.createReceiveRecover"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.AbstractPersistentActor.createReceive"),

      // #21423 removal of deprecated stages (in 2.5.x)
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.Source.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.SubSource.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.Flow.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.SubFlow.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.FlowOpsMat.transformMaterializing"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Flow.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Flow.transformMaterializing"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Flow.andThen"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Source.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Source.transformMaterializing"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Source.andThen"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.FlowOps.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.FlowOps.andThen"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.Directive"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.AsyncDirective"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.TerminationDirective"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.AbstractStage$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$Become$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.AbstractStage$PushPullGraphStage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$EmittingState$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.AbstractStage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.AbstractStage$PushPullGraphLogic"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.Context"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.Stage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.DetachedStage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$Become"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StageState"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.AbstractStage$PushPullGraphStageWithMaterializedValue"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.DownstreamDirective"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.PushPullStage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.LifecycleContext"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$EmittingState"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.PushStage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.DetachedContext"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$State"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.UpstreamDirective"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.FreeDirective"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$AndThen"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.SyncDirective"),

      // deprecated method transform(scala.Function0)akka.stream.scaladsl.FlowOps in class akka.stream.scaladsl.GraphDSL#Implicits#PortOpsImpl does not have a correspondent in current version
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.GraphDSL#Implicits#PortOpsImpl.transform"),
      // method andThen(akka.stream.impl.Stages#SymbolicStage)akka.stream.scaladsl.FlowOps in class akka.stream.scaladsl.GraphDSL#Implicits#PortOpsImpl does not have a correspondent in current version
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.GraphDSL#Implicits#PortOpsImpl.andThen"),
      // object akka.stream.stage.StatefulStage#Stay does not have a correspondent in current version
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$Stay$"),
      // object akka.stream.stage.StatefulStage#Finish does not have a correspondent in current version
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.stage.StatefulStage$Finish$"),

      // #21423 remove deprecated ActorSystem termination methods (in 2.5.x)
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystemImpl.shutdown"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystemImpl.isTerminated"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystemImpl.awaitTermination"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystemImpl.awaitTermination"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystem.shutdown"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystem.isTerminated"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystem.awaitTermination"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystem.awaitTermination"),

      // #21423 remove deprecated ActorPath.ElementRegex
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorPath.ElementRegex"),

      // #21423 remove some deprecated event bus classes
      ProblemFilters.exclude[MissingClassProblem]("akka.event.ActorClassification"),
      ProblemFilters.exclude[MissingClassProblem]("akka.event.EventStream$"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.event.EventStream.this"),
      ProblemFilters.exclude[MissingClassProblem]("akka.event.japi.ActorEventBus"),

      // #21423 remove deprecated util.Crypt
      ProblemFilters.exclude[MissingClassProblem]("akka.util.Crypt"),
      ProblemFilters.exclude[MissingClassProblem]("akka.util.Crypt$"),

      // #21423 removal of deprecated serializer constructors (in 2.5.x)
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.ProtobufSerializer.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.MessageContainerSerializer.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.DaemonMsgCreateSerializer.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.serialization.JavaSerializer.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.serialization.ByteArraySerializer.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.protobuf.ClusterMessageSerializer.this"),

      // #21423 removal of deprecated constructor in PromiseActorRef
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.pattern.PromiseActorRef.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.pattern.PromiseActorRef.apply"),

      // #21423 remove deprecated methods in routing
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.routing.Pool.nrOfInstances"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.routing.Group.paths"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.routing.PoolBase.nrOfInstances"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.routing.GroupBase.paths"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.routing.GroupBase.getPaths"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.routing.FromConfig.nrOfInstances"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.routing.RemoteRouterConfig.nrOfInstances"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.routing.ClusterRouterGroup.paths"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.routing.ClusterRouterPool.nrOfInstances"),

      // #21423 remove deprecated persist method (persistAll)
      // This might filter changes to the ordinary persist method also, but not much to do about that
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActor.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActor.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActor.persistAsync"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.Eventsourced.persist"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.Eventsourced.persistAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActor.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActor.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActor.persistAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActor.persistAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.fsm.AbstractPersistentFSM.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.fsm.AbstractPersistentFSM.persistAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.fsm.AbstractPersistentLoggingFSM.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.fsm.AbstractPersistentLoggingFSM.persistAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.sharding.PersistentShard.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.sharding.PersistentShard.persistAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.sharding.PersistentShardCoordinator.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.sharding.PersistentShardCoordinator.persistAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.sharding.RemoveInternalClusterShardingData#RemoveOnePersistenceId.persist"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.sharding.RemoveInternalClusterShardingData#RemoveOnePersistenceId.persistAsync"),

      // #21423 remove deprecated ARRAY_OF_BYTE_ARRAY
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.ProtobufSerializer.ARRAY_OF_BYTE_ARRAY"),

      // #21423 remove deprecated constructor in DeadlineFailureDetector
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.remote.DeadlineFailureDetector.this"),

      // #21423 removal of deprecated `PersistentView` (in 2.5.x)
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.Update"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.Update$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.PersistentView"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.PersistentView$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.PersistentView$ScheduledUpdate"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.AbstractPersistentView"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.UntypedPersistentView"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.PersistentView$ScheduledUpdate$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.PersistentView$State"),

      // #22015 removal of deprecated AESCounterSecureInetRNGs
      ProblemFilters.exclude[MissingClassProblem]("akka.remote.security.provider.AES128CounterInetRNG"),
      ProblemFilters.exclude[MissingClassProblem]("akka.remote.security.provider.AES256CounterInetRNG"),
      ProblemFilters.exclude[MissingClassProblem]("akka.remote.security.provider.InternetSeedGenerator"),
      ProblemFilters.exclude[MissingClassProblem]("akka.remote.security.provider.InternetSeedGenerator$"),

      // #21648 Prefer reachable nodes in consistency writes/reads
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.ReadWriteAggregator.unreachable"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.WriteAggregator.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.WriteAggregator.props"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.ReadAggregator.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.ReadAggregator.props"),

      // #22035 Make it possible to use anything as the key in a map
      FilterAnyProblemStartingWith("akka.cluster.ddata.protobuf.msg.ReplicatedDataMessages"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.ORMap"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.LWWMap"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.PNCounterMap"),
      FilterAnyProblemStartingWith("akka.cluster.ddata.ORMultiMap"),

      // #20140 durable distributed data
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#ReplicationDeleteFailure.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#DeleteSuccess.apply"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.Replicator#DeleteResponse.getRequest"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.Replicator#DeleteResponse.request"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.Replicator#Command.request"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator.receiveDelete"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#ReplicationDeleteFailure.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#ReplicationDeleteFailure.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#DeleteSuccess.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#DeleteSuccess.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#Delete.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#DataDeleted.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#DataDeleted.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#DataDeleted.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#Delete.copy"),

      // #16197 Remove backwards compatible workaround in SnapshotSerializer
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.serialization.SnapshotSerializer$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.serialization.SnapshotHeader"),
      ProblemFilters.exclude[MissingClassProblem]("akka.persistence.serialization.SnapshotHeader$"),

      // #21618 distributed data
      ProblemFilters.exclude[MissingTypesProblem]("akka.cluster.ddata.Replicator$ReadMajority$"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#ReadMajority.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#ReadMajority.apply"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.cluster.ddata.Replicator$WriteMajority$"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#WriteMajority.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.Replicator#WriteMajority.apply"),

      // #22105 Akka Typed process DSL
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorCell.addFunctionRef"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.dungeon.Children.addFunctionRef"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.Children.addFunctionRef"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.Children.addFunctionRef$default$2"),

      // implementation classes
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.SubFlowImpl.transform"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.SubFlowImpl.andThen"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.impl.Stages$SymbolicGraphStage$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.impl.Stages$SymbolicStage"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.impl.Stages$SymbolicGraphStage"),

      // ddata
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ClusterEvent#ReachabilityEvent.member"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.ddata.DurableStore#Store.apply"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.ddata.DurableStore#Store.copy$default$2"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.ddata.DurableStore#Store.data"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.ddata.DurableStore#Store.copy"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.ddata.DurableStore#Store.this"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.ddata.LmdbDurableStore.dbPut"),

      // #22218 Java Ambiguity in AbstractPersistentActor with Scala 2.12
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActor.deferAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActor.persistAllAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActor.persistAll"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.Eventsourced.deferAsync"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.Eventsourced.persistAllAsync"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.Eventsourced.persistAll"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.Eventsourced.internalPersistAsync"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.Eventsourced.internalPersist"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.Eventsourced.internalPersistAll"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.Eventsourced.internalDeferAsync"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.Eventsourced.internalPersistAllAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActorWithAtLeastOnceDelivery.deliver"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.UntypedPersistentActorWithAtLeastOnceDelivery.deliver"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.AtLeastOnceDeliveryLike.deliver"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.AtLeastOnceDeliveryLike.deliver"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.AtLeastOnceDeliveryLike.internalDeliver"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.AtLeastOnceDeliveryLike.internalDeliver"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.persistence.AbstractPersistentActorWithAtLeastOnceDelivery"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActorWithAtLeastOnceDelivery.deliver"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActorWithAtLeastOnceDelivery.deliver"),
      ProblemFilters.exclude[MissingTypesProblem]("akka.persistence.AbstractPersistentActor"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActor.deferAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActor.persistAllAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.persistence.AbstractPersistentActor.persistAll"),

      // #22208 remove extension key
      ProblemFilters.exclude[MissingClassProblem]("akka.event.Logging$Extension$"),

      // new materializer changes relating to old module structure
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.BidiShape.copyFromPorts"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.BidiShape.reversed"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.MaterializationContext.stageName"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.SinkShape.copyFromPorts"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.Shape.copyFromPorts"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.ClosedShape.copyFromPorts"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$FusedGraph$"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.stream.Attributes.extractName"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.AmorphousShape.copyFromPorts"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.SourceShape.copyFromPorts"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$FusedGraph"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.FlowShape.copyFromPorts"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.Graph.module"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.Graph.traversalBuilder"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.Source.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.RunnableGraph#RunnableGraphAdapter.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.BidiFlow.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.Sink.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.javadsl.Flow.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Sink.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Sink.this"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.stream.scaladsl.RunnableGraph.apply"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.scaladsl.GraphApply$GraphImpl"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.RunnableGraph.module"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.stream.scaladsl.RunnableGraph.copy"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.stream.scaladsl.RunnableGraph.copy$default$1"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.stream.scaladsl.RunnableGraph.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.BidiFlow.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.BidiFlow.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.GraphDSL#Builder.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Flow.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Flow.this"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.scaladsl.GraphApply$"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Source.module"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Source.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.stage.GraphStageWithMaterializedValue.module"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.scaladsl.ModuleExtractor"),
      ProblemFilters.exclude[MissingClassProblem]("akka.stream.scaladsl.ModuleExtractor$"),
      ProblemFilters.excludePackage("akka.stream.impl"),

      // small changes in attributes
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.stream.testkit.StreamTestKit#ProbeSource.withAttributes"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.stream.testkit.StreamTestKit#ProbeSink.withAttributes"),
      
      // #22332 protobuf serializers for remote deployment
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getConfigManifest"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.hasScopeManifest"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getScopeManifestBytes"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getConfigSerializerId"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.hasRouterConfigSerializerId"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.hasRouterConfigManifest"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getRouterConfigSerializerId"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getRouterConfigManifestBytes"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getConfigManifestBytes"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.hasConfigManifest"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.hasScopeSerializerId"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getRouterConfigManifest"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.hasConfigSerializerId"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getScopeSerializerId"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#DeployDataOrBuilder.getScopeManifest"),

      // #22374 introduce fishForSpecificMessage in TestKit
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.testkit.TestKitBase.fishForSpecificMessage$default$1"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.testkit.TestKitBase.fishForSpecificMessage"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.testkit.TestKitBase.fishForSpecificMessage$default$2")


      // NOTE: filters that will be backported to 2.4 should go to the latest 2.4 version below
    )


    val Release24Filters = Seq(
      "2.4.0" -> Seq(
        FilterAnyProblem("akka.remote.transport.ProtocolStateActor"),

        //#18353 Changes to methods and fields private to remoting actors
        ProblemFilters.exclude[MissingMethodProblem]("akka.remote.EndpointManager.retryGateEnabled"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.EndpointManager.pruneTimerCancellable"),

        // #18722 internal changes to actor
        FilterAnyProblem("akka.cluster.sharding.DDataShardCoordinator"),

        // #18328 optimize VersionVector for size 1
        FilterAnyProblem("akka.cluster.ddata.VersionVector"),

        ProblemFilters.exclude[MissingTypesProblem]("akka.cluster.sharding.ShardRegion$GetCurrentRegions$"),
        //FilterAnyProblemStartingWith("akka.cluster.sharding.ShardCoordinator#Internal")
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.ShardCoordinator#Internal#State.apply"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.ShardCoordinator#Internal#State.copy"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.ShardCoordinator#Internal#State.this")
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
        ProblemFilters.exclude[MissingMethodProblem]("akka.pattern.FutureTimeoutSupport.afterCompletionStage"),

        ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("akka.persistence.PersistenceStash.internalStashOverflowStrategy")
      ),
      "2.4.2" -> Seq(
        //internal API
        FilterAnyProblemStartingWith("akka.http.impl"),

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

        // #20009 internal and shouldn't have been public
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.QueueSource.completion"),

        // #20015 simplify materialized value computation tree
        ProblemFilters.exclude[FinalMethodProblem]("akka.stream.impl.StreamLayout#AtomicModule.subModules"),
        ProblemFilters.exclude[FinalMethodProblem]("akka.stream.impl.StreamLayout#AtomicModule.downstreams"),
        ProblemFilters.exclude[FinalMethodProblem]("akka.stream.impl.StreamLayout#AtomicModule.upstreams"),
        ProblemFilters.exclude[FinalMethodProblem]("akka.stream.impl.Stages#DirectProcessor.toString"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.stream.impl.MaterializerSession.materializeAtomic"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.impl.MaterializerSession.materializeAtomic"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.impl.Stages$StageModule"),
        ProblemFilters.exclude[FinalMethodProblem]("akka.stream.impl.Stages#GroupBy.toString"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.impl.FlowModule"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.FlowModule.subModules"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.impl.FlowModule.label"),
        ProblemFilters.exclude[FinalClassProblem]("akka.stream.impl.fusing.GraphModule"),

        // #15947 catch mailbox creation failures
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.RepointableActorRef.point"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.Dispatch.initWithFailure"),

        // #19877 Source.queue termination support
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.stream.impl.SourceQueueAdapter.this"),

        // #19828
        ProblemFilters.exclude[DirectAbstractMethodProblem]("akka.persistence.Eventsourced#ProcessingState.onWriteMessageComplete"),
        ProblemFilters.exclude[ReversedAbstractMethodProblem]("akka.persistence.Eventsourced#ProcessingState.onWriteMessageComplete"),

        // #19390 Add flow monitor
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOpsMat.monitor"),
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.impl.fusing.GraphStages$TickSource$"),

        FilterAnyProblemStartingWith("akka.http.impl"),

        // #20214
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.DefaultSSLContextCreation.createClientHttpsContext"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.DefaultSSLContextCreation.validateAndWarnAboutLooseSettings")
      ),
      "2.4.4" -> Seq(
        // #20080, #20081 remove race condition on HTTP client
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.scaladsl.Http#HostConnectionPool.gatewayFuture"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.http.scaladsl.Http#HostConnectionPool.copy"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.http.scaladsl.Http#HostConnectionPool.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.scaladsl.HttpExt.hostPoolCache"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.scaladsl.HttpExt.cachedGateway"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.http.scaladsl.Http#HostConnectionPool.apply"),
        ProblemFilters.exclude[FinalClassProblem]("akka.http.impl.engine.client.PoolGateway"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.engine.client.PoolGateway.currentState"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.engine.client.PoolGateway.apply"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.http.impl.engine.client.PoolGateway.this"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.client.PoolGateway$NewIncarnation$"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.client.PoolGateway$Running$"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.client.PoolGateway$IsShutdown$"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.engine.client.PoolInterfaceActor.this"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.client.PoolGateway$Running"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.client.PoolGateway$IsShutdown"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.client.PoolGateway$NewIncarnation"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.client.PoolGateway$State"),

        // #20371, missing method and typo in another one making it impossible to use HTTPs via setting default HttpsConnectionContext
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.http.scaladsl.HttpExt.setDefaultClientHttpsContext"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.DefaultSSLContextCreation.createServerHttpsContext"),

        // #20342 HttpEntity scaladsl overrides
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.HttpEntity.withoutSizeLimit"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.HttpEntity.withSizeLimit"),

        // #20293 Use JDK7 NIO Path instead of File
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.HttpMessage#MessageTransformations.withEntity"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.HttpMessage.withEntity"),

        // #20401 custom media types registering
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.impl.model.parser.CommonActions.customMediaTypes"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.model.parser.HeaderParser.Settings"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.impl.model.parser.HeaderParser#Settings.customMediaTypes"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.impl.engine.parsing.HttpHeaderParser#Settings.customMediaTypes"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.settings.ParserSettingsImpl.apply"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.settings.ParserSettingsImpl.copy"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.settings.ParserSettingsImpl.this"),

        // #20123
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.recoverWithRetries"),

        // #20379 Allow registering custom media types
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.settings.ParserSettings.getCustomMediaTypes"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.settings.ParserSettings.customMediaTypes"),

        // internal api
        FilterAnyProblemStartingWith("akka.stream.impl"),
        FilterAnyProblemStartingWith("akka.http.impl"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.util.package.printEvent"),

        // #20362 - parser private
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.impl.model.parser.CommonRules.expires-date"),

        // #20319 - remove not needed "no. of persists" counter in sharding
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.PersistentShard.persistCount"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.PersistentShard.persistCount_="),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.PersistentShardCoordinator.persistCount"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.PersistentShardCoordinator.persistCount_="),

        // #19225 - GraphStage and removal of isTerminated
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.engine.parsing.HttpMessageParser.isTerminated"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.impl.engine.parsing.HttpMessageParser.stage"),

        // #20131 - flow combinator
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.backpressureTimeout"),

        // #20470 - new JavaDSL for Akka HTTP
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.DateTime.plus"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.DateTime.minus"),

        // #20214
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.DefaultSSLContextCreation.createClientHttpsContext"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.DefaultSSLContextCreation.validateAndWarnAboutLooseSettings"),

        // #20257 Snapshots with PersistentFSM (experimental feature)
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.serialization.MessageFormats#PersistentStateChangeEventOrBuilder.getTimeoutNanos"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.serialization.MessageFormats#PersistentStateChangeEventOrBuilder.hasTimeoutNanos"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.fsm.PersistentFSM.saveStateSnapshot"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.fsm.PersistentFSM.akka$persistence$fsm$PersistentFSM$$currentStateTimeout"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.fsm.PersistentFSM.akka$persistence$fsm$PersistentFSM$$currentStateTimeout_="),

        // #19834
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.extra.Timed$StartTimed"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.extra.Timed#StartTimed.onPush"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.extra.Timed$TimedInterval"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.extra.Timed#TimedInterval.onPush"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.extra.Timed$StopTimed"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.extra.Timed#StopTimed.onPush"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.extra.Timed#StopTimed.onUpstreamFinish"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.extra.Timed#StopTimed.onUpstreamFailure"),

        // #20462 - now uses a Set instead of a Seq within the private API of the cluster client
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.client.ClusterClient.contacts_="),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.client.ClusterClient.contacts"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.client.ClusterClient.initialContactsSel"),

        // * field EMPTY in class akka.http.javadsl.model.HttpEntities's type is different in current version, where it is: akka.http.javadsl.model.HttpEntity#Strict rather than: akka.http.scaladsl.model.HttpEntity#Strict
        ProblemFilters.exclude[IncompatibleFieldTypeProblem]("akka.http.javadsl.model.HttpEntities.EMPTY"),
        //  method createIndefiniteLength(akka.http.javadsl.model.ContentType,akka.stream.javadsl.Source)akka.http.scaladsl.model.HttpEntity#IndefiniteLength in class akka.http.javadsl.model.HttpEntities has a different result type in current version, where it is akka.http.javadsl.model.HttpEntity#IndefiniteLength rather than akka.http.scaladsl.model.HttpEntity#IndefiniteLength
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.javadsl.model.HttpEntities.createIndefiniteLength"),
        // method createCloseDelimited(akka.http.javadsl.model.ContentType,akka.stream.javadsl.Source)akka.http.scaladsl.model.HttpEntity#CloseDelimited in class akka.http.javadsl.model.HttpEntities has a different result type in current version, where it is akka.http.javadsl.model.HttpEntity#CloseDelimited rather than akka.http.scaladsl.model.HttpEntity#CloseDelimited
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.javadsl.model.HttpEntities.createCloseDelimited"),
        // method createChunked(akka.http.javadsl.model.ContentType,akka.stream.javadsl.Source)akka.http.scaladsl.model.HttpEntity#Chunked in class akka.http.javadsl.model.HttpEntities has a different result type in current version, where it is akka.http.javadsl.model.HttpEntity#Chunked rather than akka.http.scaladsl.model.HttpEntity#Chunked
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.javadsl.model.HttpEntities.createChunked"),
        // method create(akka.http.javadsl.model.ContentType,akka.stream.javadsl.Source)akka.http.scaladsl.model.HttpEntity#Chunked in class akka.http.javadsl.model.HttpEntities has a different result type in current version, where it is akka.http.javadsl.model.HttpEntity#Chunked rather than akka.http.scaladsl.model.HttpEntity#Chunked
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.javadsl.model.HttpEntities.create")
      ),
      "2.4.6" -> Seq(
        // internal api
        FilterAnyProblemStartingWith("akka.stream.impl"),

        // #20214 SNI disabling for single connections (AkkaSSLConfig being passed around)
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.ConnectionContext.sslConfig"), // class meant only for internal extension

        //#20229 migrate GroupBy to GraphStage
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.GraphDSL#Builder.deprecatedAndThen"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Flow.deprecatedAndThen"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Flow.deprecatedAndThenMat"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Source.deprecatedAndThen"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.FlowOps.deprecatedAndThen"),

        // #20367 Converts DelimiterFramingStage from PushPullStage to GraphStage
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.scaladsl.Framing$DelimiterFramingStage"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#DelimiterFramingStage.onPush"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#DelimiterFramingStage.onUpstreamFinish"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#DelimiterFramingStage.onPull"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#DelimiterFramingStage.postStop"),

        // #20345 converts LengthFieldFramingStage to GraphStage
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.scaladsl.Framing$LengthFieldFramingStage"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#LengthFieldFramingStage.onPush"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#LengthFieldFramingStage.onUpstreamFinish"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#LengthFieldFramingStage.onPull"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.Framing#LengthFieldFramingStage.postStop"),

        // #20414 Allow different ActorMaterializer subtypes
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.ActorMaterializer.downcast"),

        // #20531 adding refuseUid to Gated
        FilterAnyProblem("akka.remote.EndpointManager$Gated"),

        // #20683
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.HttpMessage.discardEntityBytes"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.HttpMessage.discardEntityBytes"),

        // #20288 migrate BodyPartRenderer to GraphStage
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.impl.engine.rendering.BodyPartRenderer.streamed"),

        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.stream.scaladsl.TLS.apply$default$5"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.TLS.apply$default$4"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.scaladsl.GraphDSL#Implicits#PortOpsImpl.deprecatedAndThen")
      ),
      "2.4.7" -> Seq(
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.ActorMaterializer.downcast"),
        FilterAnyProblemStartingWith("akka.cluster.pubsub.DistributedPubSubMediator$Internal"),

        // abstract method discardEntityBytes(akka.stream.Materializer)akka.http.javadsl.model.HttpMessage#DiscardedEntity in interface akka.http.javadsl.model.HttpMessage is present only in current version
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.HttpMessage.discardEntityBytes"),
        // method discardEntityBytes(akka.stream.Materializer)akka.http.scaladsl.model.HttpMessage#DiscardedEntity in trait akka.http.scaladsl.model.HttpMessage is present only in current version
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.HttpMessage.discardEntityBytes")
      ),
      "2.4.8" -> Seq(
        // #20717 example snippet for akka http java dsl: SecurityDirectives
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.HttpMessage#MessageTransformations.addCredentials"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.HttpMessage.addCredentials"),

        // #20456 adding hot connection pool option
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.settings.ConnectionPoolSettings.getMinConnections"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.settings.ConnectionPoolSettings.minConnections"),
        FilterAnyProblemStartingWith("akka.http.impl"),

        // #20846 change of internal Status message
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.pubsub.protobuf.msg.DistributedPubSubMessages#StatusOrBuilder.getReplyToStatus"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.pubsub.protobuf.msg.DistributedPubSubMessages#StatusOrBuilder.hasReplyToStatus"),

        // #20543 GraphStage subtypes should not be private to akka
        ProblemFilters.exclude[DirectAbstractMethodProblem]("akka.stream.ActorMaterializer.actorOf"),

        // Interpreter internals change
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.stream.stage.GraphStageLogic.portToConn"),

        // #20994 adding new decode method, since we're on JDK7+ now
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.util.ByteString.decodeString"),

        // #20508  HTTP: Document how to be able to support custom request methods
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.HttpMethod.getRequestEntityAcceptance"),

        // #20976 provide different options to deal with the illegal response header value
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.settings.ParserSettings.getIllegalResponseHeaderValueProcessingMode"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.settings.ParserSettings.illegalResponseHeaderValueProcessingMode"),

        ProblemFilters.exclude[DirectAbstractMethodProblem]("akka.stream.ActorMaterializer.actorOf"),

        // #20628 migrate Masker to GraphStage
        ProblemFilters.exclude[MissingTypesProblem]("akka.http.impl.engine.ws.Masking$Masking"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.http.impl.engine.ws.Masking$Masker"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.http.impl.engine.ws.Masking#Masker.initial"),
        ProblemFilters.exclude[MissingClassProblem]("akka.http.impl.engine.ws.Masking$Masker$Running"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.http.impl.engine.ws.Masking$Unmasking"),

        // #
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.javadsl.model.HttpEntity.discardBytes"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.HttpEntity.discardBytes"),

        // #20630 corrected return types of java methods
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.stream.javadsl.RunnableGraph#RunnableGraphAdapter.named"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.javadsl.RunnableGraph.withAttributes"),

        // #19872 double wildcard for actor deployment config
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.actor.Deployer.lookup"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.util.WildcardTree.apply"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.util.WildcardTree.find"),

        // #20942 ClusterSingleton
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.singleton.ClusterSingletonManager.addRemoved"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.singleton.ClusterSingletonManager.selfAddressOption")
      ),
      "2.4.9" -> Seq(
        // #21025 new orElse flow op
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.orElseGraph"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.orElse"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOpsMat.orElseMat"),

        // #21201 adding childActorOf to TestActor / TestKit / TestProbe
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.testkit.TestKitBase.childActorOf$default$3"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.testkit.TestKitBase.childActorOf$default$2"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.testkit.TestKitBase.childActorOf"),

        // #21184 add java api for ws testkit
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.ws.TextMessage.asScala"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.ws.TextMessage.getStreamedText"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.ws.BinaryMessage.asScala"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.http.scaladsl.model.ws.BinaryMessage.getStreamedData"),

        // #21273 minor cleanup of WildcardIndex
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.util.WildcardIndex.empty"),

        // #20888 new FoldAsync op for Flow
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.foldAsync"),

        // method ChaseLimit()Int in object akka.stream.impl.fusing.GraphInterpreter does not have a correspondent in current version
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.fusing.GraphInterpreter.ChaseLimit"),
        FilterAnyProblemStartingWith("akka.http.impl.engine")
      ),
      "2.4.10" -> Seq(
        // #21290 new zipWithIndex flow op
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.zipWithIndex"),

        // Remove useUntrustedMode which is an internal API and not used anywhere anymore
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.Remoting.useUntrustedMode"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.RemoteTransport.useUntrustedMode"),

        // Use OptionVal in remote Send envelope
        FilterAnyProblemStartingWith("akka.remote.EndpointManager"),
        FilterAnyProblemStartingWith("akka.remote.Remoting"),
        FilterAnyProblemStartingWith("akka.remote.RemoteTransport"),
        FilterAnyProblemStartingWith("akka.remote.InboundMessageDispatcher"),
        FilterAnyProblemStartingWith("akka.remote.DefaultMessageDispatcher"),
        FilterAnyProblemStartingWith("akka.remote.transport"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.RemoteActorRefProvider.quarantine"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.RemoteWatcher.quarantine"),

        // #20644 long uids
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.protobuf.msg.ClusterMessages#UniqueAddressOrBuilder.hasUid2"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.protobuf.msg.ClusterMessages#UniqueAddressOrBuilder.getUid2"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.protobuf.msg.ReplicatorMessages#UniqueAddressOrBuilder.hasUid2"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.protobuf.msg.ReplicatorMessages#UniqueAddressOrBuilder.getUid2"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.remote.RemoteWatcher.receiveHeartbeatRsp"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.RemoteWatcher.selfHeartbeatRspMsg"),

        // #21131 new implementation for Akka Typed
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.DeathWatch.isWatching"),

        // class akka.stream.impl.fusing.Map is declared final in current version
        ProblemFilters.exclude[FinalClassProblem]("akka.stream.impl.fusing.Map")
      ),
      "2.4.11" -> Seq(
        // #20795  IOResult construction exposed
        ProblemFilters.exclude[MissingTypesProblem]("akka.stream.IOResult$"),

        // #21727 moved all of Unfold.scala in package akka.stream.impl
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.scaladsl.UnfoldAsync"),
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.scaladsl.Unfold"),

        // #21194 renamed internal actor method
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.ShardCoordinator.allocateShardHomes"),

        // MarkerLoggingAdapter introduced (all internal classes)
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.actor.LocalActorRefProvider.log"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.actor.VirtualPathContainer.log"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.actor.VirtualPathContainer.this"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.remote.RemoteSystemDaemon.this"),

        //  method this(akka.actor.ExtendedActorSystem,akka.remote.RemoteActorRefProvider,akka.event.LoggingAdapter)Unit in class akka.remote.DefaultMessageDispatcher's type is different in current version, where it is (akka.actor.ExtendedActorSystem,akka.remote.RemoteActorRefProvider,akka.event.MarkerLoggingAdapter)Unit instead of (akka.actor.ExtendedActorSystem,akka.remote.RemoteActorRefProvider,akka.event.LoggingAdapter)Unit
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.remote.DefaultMessageDispatcher.this"),
        // trait akka.remote.artery.StageLogging does not have a correspondent in current version
        ProblemFilters.exclude[MissingClassProblem]("akka.remote.artery.StageLogging"),
        // method SSLProtocol()scala.Option in class akka.remote.transport.netty.SSLSettings has a different result type in current version, where it is java.lang.String rather than scala.Option
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.transport.netty.SSLSettings.SSLProtocol"),
        // method SSLTrustStorePassword()scala.Option in class akka.remote.transport.netty.SSLSettings has a different result type in current version, where it is java.lang.String rather than scala.Option
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.transport.netty.SSLSettings.SSLTrustStorePassword"),
        // method SSLKeyStorePassword()scala.Option in class akka.remote.transport.netty.SSLSettings has a different result type in current version, where it is java.lang.String rather than scala.Option
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.transport.netty.SSLSettings.SSLKeyStorePassword"),
        // method SSLRandomNumberGenerator()scala.Option in class akka.remote.transport.netty.SSLSettings has a different result type in current version, where it is java.lang.String rather than scala.Option
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.transport.netty.SSLSettings.SSLRandomNumberGenerator"),
        // method SSLKeyPassword()scala.Option in class akka.remote.transport.netty.SSLSettings has a different result type in current version, where it is java.lang.String rather than scala.Option
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.transport.netty.SSLSettings.SSLKeyPassword"),
        // method SSLKeyStore()scala.Option in class akka.remote.transport.netty.SSLSettings has a different result type in current version, where it is java.lang.String rather than scala.Option
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.transport.netty.SSLSettings.SSLKeyStore"),
        //  method SSLTrustStore()scala.Option in class akka.remote.transport.netty.SSLSettings has a different result type in current version, where it is java.lang.String rather than scala.Option
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.remote.transport.netty.SSLSettings.SSLTrustStore"),
        // method initializeClientSSL(akka.remote.transport.netty.SSLSettings,akka.event.LoggingAdapter)org.jboss.netty.handler.ssl.SslHandler in object akka.remote.transport.netty.NettySSLSupport does not have a correspondent in current version
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.transport.netty.NettySSLSupport.initializeClientSSL"),
        // method apply(akka.remote.transport.netty.SSLSettings,akka.event.LoggingAdapter,Boolean)org.jboss.netty.handler.ssl.SslHandler in object akka.remote.transport.netty.NettySSLSupport's type is different in current version, where it is (akka.remote.transport.netty.SSLSettings,akka.event.MarkerLoggingAdapter,Boolean)org.jboss.netty.handler.ssl.SslHandler instead of (akka.remote.transport.netty.SSLSettings,akka.event.LoggingAdapter,Boolean)org.jboss.netty.handler.ssl.SslHandler
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.remote.transport.netty.NettySSLSupport.apply"),
        // initializeCustomSecureRandom(scala.Option,akka.event.LoggingAdapter)java.security.SecureRandom in object akka.remote.transport.netty.NettySSLSupport does not have a correspondent in current version
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.transport.netty.NettySSLSupport.initializeCustomSecureRandom"),
        // method initializeServerSSL(akka.remote.transport.netty.SSLSettings,akka.event.LoggingAdapter)org.jboss.netty.handler.ssl.SslHandler in object akka.remote.transport.netty.NettySSLSupport does not have a correspondent in current version
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.transport.netty.NettySSLSupport.initializeServerSSL"),
        // abstract method makeLogger(java.lang.Class)akka.event.LoggingAdapter in interface akka.stream.MaterializerLoggingProvider is inherited by class ActorMaterializer in current version.
        ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("akka.stream.MaterializerLoggingProvider.makeLogger"),
        FilterAnyProblemStartingWith("akka.stream.impl"),
        // synthetic method currentEventsByTag$default$2()Long in class akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal has a different result type in current version, where it is akka.persistence.query.Offset rather than Long
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal.currentEventsByTag$default$2"),
        // synthetic method eventsByTag$default$2()Long in class akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal has a different result type in current version, where it is akka.persistence.query.Offset rather than Long
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal.eventsByTag$default$2"),

        // #21330 takeWhile inclusive flag
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.takeWhile"),

        // #21541 new ScanAsync flow op
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.scanAsync")
      ),
      "2.4.12" -> Seq(
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.Materializer.materialize"),

        // #21775 - overrode ByteString.stringPrefix and made it final
        ProblemFilters.exclude[FinalMethodProblem]("akka.util.ByteString.stringPrefix"),

        // #20553 Tree flattening should be separate from Fusing
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$StructuralInfo"),
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$StructuralInfo$")
      ),
      "2.4.13" -> Seq(
        // extension method isEmpty$extension(Int)Boolean in object akka.remote.artery.compress.TopHeavyHitters#HashCodeVal does not have a correspondent in current version
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.artery.compress.TopHeavyHitters#HashCodeVal.isEmpty$extension"),
        // isEmpty()Boolean in class akka.remote.artery.compress.TopHeavyHitters#HashCodeVal does not have a correspondent in current version
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.artery.compress.TopHeavyHitters#HashCodeVal.isEmpty")
      ),
      "2.4.14" -> Seq(
        // # 21944
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ClusterEvent#ReachabilityEvent.member"),

        // #21645 durable distributed data
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.WriteAggregator.props"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.WriteAggregator.this"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.ddata.Replicator.write"),

        // #21394 remove static config path of levelDBJournal and localSnapshotStore
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.snapshot.local.LocalSnapshotStore.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.journal.leveldb.LeveldbStore.configPath"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.journal.leveldb.LeveldbJournal.configPath"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.persistence.journal.leveldb.SharedLeveldbStore.configPath"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.persistence.journal.leveldb.LeveldbStore.prepareConfig"),

        // #20737 aligned test sink and test source stage factory methods types
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.stream.testkit.TestSinkStage.apply"),

        FilterAnyProblemStartingWith("akka.stream.impl"),
        FilterAnyProblemStartingWith("akka.remote.artery"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.remote.MessageSerializer.serializeForArtery"),

        // https://github.com/akka/akka/pull/21688
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$StructuralInfo$"),
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$StructuralInfo"),

        // https://github.com/akka/akka/pull/21989 - add more information in tcp connection shutdown logs (add mapError)
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.mapError"),

        // #21894 Programmatic configuration of the ActorSystem
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.actor.ActorSystemImpl.this")
      ),
      "2.4.16" -> Seq(
        // internal classes
        FilterAnyProblemStartingWith("akka.remote.artery")
      ),
      "2.4.17" -> Seq(
        // #22711 changes to groupedWithin internal classes
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.groupedWeightedWithin"),

        // #22277 changes to internal classes
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.transport.netty.TcpServerHandler.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.transport.netty.TcpClientHandler.this"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.transport.netty.TcpHandlers.log"),

        // #22224 DaemonMsgCreateSerializer using manifests
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData.getClassesBytes"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData.getClassesList"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData.getClassesCount"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData.getClasses"),
        ProblemFilters.exclude[MissingFieldProblem]("akka.remote.WireFormats#PropsData.CLASSES_FIELD_NUMBER"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getHasManifest"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getHasManifestCount"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getSerializerIdsList"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getSerializerIds"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getHasManifestList"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getSerializerIdsCount"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getClassesBytes"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getClassesList"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getClassesCount"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getClasses"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getManifestsBytes"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getManifests"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getManifestsList"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.WireFormats#PropsDataOrBuilder.getManifestsCount"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.getClassesBytes"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.getClassesList"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.addClassesBytes"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.getClassesCount"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.clearClasses"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.addClasses"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.getClasses"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.addAllClasses"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.WireFormats#PropsData#Builder.setClasses"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.DaemonMsgCreateSerializer.serialize"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.DaemonMsgCreateSerializer.deserialize"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.DaemonMsgCreateSerializer.deserialize"),
        ProblemFilters.exclude[FinalClassProblem]("akka.remote.serialization.DaemonMsgCreateSerializer"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.DaemonMsgCreateSerializer.serialization"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.serialization.DaemonMsgCreateSerializer.this"),

        // #22657 changes to internal classes
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FilePublisher.props"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FilePublisher.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSink.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSource.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSubscriber.props"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSubscriber.this"),

        // Internal MessageBuffer for actors
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.pubsub.PerGroupingBuffer.akka$cluster$pubsub$PerGroupingBuffer$$buffers"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.pubsub.PerGroupingBuffer.akka$cluster$pubsub$PerGroupingBuffer$_setter_$akka$cluster$pubsub$PerGroupingBuffer$$buffers_="),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.singleton.ClusterSingletonProxy.buffer"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.singleton.ClusterSingletonProxy.buffer_="),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.client.ClusterClient.buffer"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.Shard.totalBufferSize"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.sharding.Shard.messageBuffers"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.Shard.messageBuffers_="),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.ShardRegion.totalBufferSize"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.sharding.ShardRegion.shardBuffers"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.cluster.sharding.ShardRegion.shardBuffers_=")
      ),
      "2.4.18" -> Seq(
      ),
      "2.4.19" -> Seq(
      )
      // make sure that
      //  * this list ends with the latest released version number
      //  * is kept in sync between release-2.4 and master branch
    )

    val Release25Filters = Seq(
      "2.5.0" -> Seq(

        // #22759 LMDB files
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.LmdbDurableStore.env"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.LmdbDurableStore.db"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.LmdbDurableStore.keyBuffer"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.LmdbDurableStore.valueBuffer_="),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.LmdbDurableStore.valueBuffer"),

        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.groupedWeightedWithin"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSubscriber.props"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSource.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSink.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FilePublisher.props"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FileSubscriber.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.io.FilePublisher.this"),
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.impl.fusing.GroupedWithin"),

        ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("akka.stream.Graph.traversalBuilder"),
        ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("akka.stream.Graph.named"),
        ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("akka.stream.Graph.addAttributes"),
        ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("akka.stream.Graph.async")
      ),
      "2.5.1" -> Seq(
        // #22794 watchWith
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.ActorContext.watchWith"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.DeathWatch.watchWith"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.DeathWatch.akka$actor$dungeon$DeathWatch$$watching"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.DeathWatch.akka$actor$dungeon$DeathWatch$$watching_="),
        
        // #22868 store shards
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.sendUpdate"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.waitingForUpdate"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.getState"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.waitingForState"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.sharding.DDataShardCoordinator.this"),
          
        // #21213 Feature request: Let BackoffSupervisor reply to messages when its child is stopped
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.pattern.BackoffSupervisor.this"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.pattern.BackoffOptionsImpl.copy"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.pattern.BackoffOptionsImpl.this"),
        ProblemFilters.exclude[MissingTypesProblem]("akka.pattern.BackoffOptionsImpl$"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.pattern.BackoffOptionsImpl.apply"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.pattern.BackoffOnRestartSupervisor.this"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.pattern.HandleBackoff.replyWhileStopped"),
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.pattern.BackoffOptions.withReplyWhileStopped")
      ),
      "2.5.2" -> Seq(
        // #22881 Make sure connections are aborted correctly on Windows
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.io.ChannelRegistration.cancel"),
        
        // #21880 PartitionHub in Artery
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.remote.artery.ArterySettings#Advanced.InboundBroadcastHubBufferSize"),
        
        // #23144 recoverWithRetries cleanup
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.stream.impl.fusing.RecoverWith.InfiniteRetries"),  

        // #23025 OversizedPayloadException DeltaPropagation
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ddata.DeltaPropagationSelector.maxDeltaSize"),
        
        // #23023 added a new overload with implementation to trait, so old transport implementations compiled against
        // older versions will be missing the method. We accept that incompatibility for now.
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.remote.transport.AssociationHandle.disassociate")
      )
      // make sure that this list ends with the latest released version number
    )

    val Latest24Filters = Release24Filters.last
    val AllFilters =
      Release25Filters ++ Release24Filters.dropRight(1) :+ (Latest24Filters._1 -> (Latest24Filters._2 ++ bcIssuesBetween24and25))

    Map(AllFilters: _*)
  }
}
