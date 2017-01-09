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
      val akka25Versions = Seq.empty[String] // FIXME enable once 2.5.0 is out (0 to latestMinorVersionOf("2.5.")).map(patch => s"2.5.$patch")
      val akka24StreamVersions = (2 to 12) map ("2.4." + _)
      val akka24WithScala212 = (13 to latestMinorVersionOf("2.4.")) map ("2.4." + _)
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
          } ++ akka24StreamVersions ++ akka24WithScala212
          
        case "2.12" => 
          akka24WithScala212
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

  def mimaIgnoredProblems = {
    import com.typesafe.tools.mima.core._

    val bcIssuesBetween24and25 = Seq(
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
      ProblemFilters.exclude[MissingClassProblem]("akka.remote.security.provider.InternetSeedGenerator$")
    )

    Map(
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
      "2.4.14" -> (Seq(
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
      ) ++ bcIssuesBetween24and25)
      // Entries should be added to a section keyed with the latest released version before the change
    )
  }
}
