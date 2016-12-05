/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import com.typesafe.tools.mima.core.ProblemFilters
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
      val akka24NoStreamVersions = Seq("2.4.0", "2.4.1")
      val akka24StreamVersions = Seq("2.4.2", "2.4.3", "2.4.4", "2.4.6")
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
        "akka-http-core",
        
        "akka-http-testkit",
        "akka-stream-testkit"
      )
      scalaBinaryVersion match {
        case "2.11" if !(akka24NewArtifacts ++ akka242NewArtifacts).contains(projectName) => akka24NoStreamVersions ++ akka24StreamVersions
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

    Map(
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
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.client.ClusterClient.initialContactsSel")
      ),
      "2.4.6" -> Seq(
        // internal api
        FilterAnyProblemStartingWith("akka.stream.impl"),

        // #20888 new FoldAsync op for Flow
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.foldAsync"),

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
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.http.impl.engine.rendering.BodyPartRenderer.streamed")
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

        // #21131 new implementation for Akka Typed
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.actor.dungeon.DeathWatch.isWatching")
      ),
      "2.4.10" -> Seq(
        // #21290 new zipWithIndex flow op
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.zipWithIndex"),

        // #21541 new ScanAsync flow op
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.scanAsync"),

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

        // #21330 takeWhile inclusive flag
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.takeWhile")
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
        ProblemFilters.exclude[IncompatibleMethTypeProblem]("akka.remote.RemoteSystemDaemon.this")
      ),
      "2.4.12" -> Seq(
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.Materializer.materialize"),
        
        // #21775 - overrode ByteString.stringPrefix and made it final
        ProblemFilters.exclude[FinalMethodProblem]("akka.util.ByteString.stringPrefix"),

        // #20553 Tree flattening should be separate from Fusing
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$StructuralInfo"),
        ProblemFilters.exclude[MissingClassProblem]("akka.stream.Fusing$StructuralInfo$")
      ), 
      "2.4.14" -> Seq(
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

        // # 21944
        ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.cluster.ClusterEvent#ReachabilityEvent.member"),
        
        // #21645 durable distributed data
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.WriteAggregator.props"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("akka.cluster.ddata.WriteAggregator.this"),
        ProblemFilters.exclude[IncompatibleResultTypeProblem]("akka.cluster.ddata.Replicator.write")
      )
    )
  }
}
