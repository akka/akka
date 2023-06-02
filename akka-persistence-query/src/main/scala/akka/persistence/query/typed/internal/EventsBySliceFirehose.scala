/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed.internal

import java.time.Instant
import java.time.{ Duration => JDuration }
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import com.typesafe.config.Config

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.ClassicActorSystemProvider
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.TimestampOffset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.typed.scaladsl.EventsBySliceQuery
import akka.stream.Attributes
import akka.stream.FanInShape2
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.KillSwitch
import akka.stream.KillSwitches
import akka.stream.Outlet
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.StageLogging
import akka.util.JavaDurationConverters._
import akka.util.OptionVal
import akka.util.unused

/**
 * INTERNAL API
 */
@InternalApi private[akka] object EventsBySliceFirehose
    extends ExtensionId[EventsBySliceFirehose]
    with ExtensionIdProvider {

  override def get(system: ActorSystem): EventsBySliceFirehose = super.get(system)

  override def get(system: ClassicActorSystemProvider): EventsBySliceFirehose = super.get(system)

  override def lookup = EventsBySliceFirehose

  override def createExtension(system: ExtendedActorSystem): EventsBySliceFirehose =
    new EventsBySliceFirehose(system)

  object Settings {
    def apply(system: ActorSystem, pluginId: String): Settings =
      apply(system.settings.config.getConfig(pluginId))

    def apply(config: Config): Settings =
      Settings(
        delegateQueryPluginId = config.getString("delegate-query-plugin-id"),
        broadcastBufferSize = config.getInt("broadcast-buffer-size"),
        firehoseLingerTimeout = config.getDuration("firehose-linger-timeout").asScala,
        catchupOverlap = config.getDuration("catchup-overlap"),
        slowConsumerReaperInterval = config.getDuration("slow-consumer-reaper-interval").asScala,
        slowConsumerBehindThreshold = config.getDuration("slow-consumer-behind-threshold"),
        abortSlowConsumerAfter = config.getDuration("abort-slow-consumer-after"))
  }

  final case class Settings(
      delegateQueryPluginId: String,
      broadcastBufferSize: Int,
      firehoseLingerTimeout: FiniteDuration,
      catchupOverlap: JDuration,
      slowConsumerReaperInterval: FiniteDuration,
      slowConsumerBehindThreshold: JDuration,
      abortSlowConsumerAfter: JDuration) {
    require(
      delegateQueryPluginId != null && delegateQueryPluginId.nonEmpty,
      "Configuration of delegate-query-plugin-id must defined.")
  }

  final class SlowConsumerException(message: String) extends RuntimeException(message) with NoStackTrace

  final case class FirehoseKey(pluginId: String, entityType: String, sliceRange: Range)

  final case class ConsumerTracking(
      consumerId: String,
      timestamp: Instant,
      firehoseOnly: Boolean,
      consumerKillSwitch: KillSwitch,
      slowConsumerCandidate: Option[Instant])

  final class Firehose(
      val firehoseKey: FirehoseKey,
      val settings: Settings,
      val firehoseHub: Source[EventEnvelope[Any], NotUsed],
      firehoseKillSwitch: KillSwitch,
      log: LoggingAdapter) {

    val consumerTracking: ConcurrentHashMap[String, ConsumerTracking] = new ConcurrentHashMap
    @volatile private var firehoseIsShutdown = false

    private def entityType = firehoseKey.entityType
    private def sliceRange = firehoseKey.sliceRange

    def consumerStarted(consumerId: String, consumerKillSwitch: KillSwitch): Unit = {
      log.debug("Firehose entityType [{}] sliceRange [{}] consumer [{}] started", entityType, sliceRange, consumerId)
      consumerTracking.putIfAbsent(
        consumerId,
        ConsumerTracking(consumerId, Instant.EPOCH, firehoseOnly = false, consumerKillSwitch, None))
    }

    def consumerTerminated(consumerId: String): Int = {
      log.debug("Firehose entityType [{}] sliceRange [{}] consumer [{}] terminated", entityType, sliceRange, consumerId)
      consumerTracking.remove(consumerId)
      consumerTracking.size
    }

    def shutdownFirehoseIfNoConsumers(): Boolean = {
      if (consumerTracking.isEmpty) {
        log.debug("Firehose entityType [{}] sliceRange [{}] is shutting down, no consumers", entityType, sliceRange)
        firehoseIsShutdown = true
        firehoseKillSwitch.shutdown()
        true
      } else
        false
    }

    def isShutdown: Boolean =
      firehoseIsShutdown

    @tailrec def updateConsumerTracking(
        consumerId: String,
        timestamp: Instant,
        consumerKillSwitch: KillSwitch): Unit = {

      val existingTracking = consumerTracking.get(consumerId)
      val tracking = existingTracking match {
        case null =>
          ConsumerTracking(consumerId, timestamp, firehoseOnly = false, consumerKillSwitch, None)
        case existing =>
          if (timestamp.isAfter(existing.timestamp))
            existing.copy(timestamp = timestamp)
          else
            existing
      }

      if (!consumerTracking.replace(consumerId, existingTracking, tracking)) {
        // concurrent update, try again
        updateConsumerTracking(consumerId, timestamp, consumerKillSwitch)
      }
    }

    def detectSlowConsumers(now: Instant): Unit = {
      import akka.util.ccompat.JavaConverters._
      val consumerTrackingValues = consumerTracking.values.iterator.asScala.toVector
      if (consumerTrackingValues.size > 1) {
        val slowestConsumer = consumerTrackingValues.minBy(_.timestamp)
        val fastestConsumer = consumerTrackingValues.maxBy(_.timestamp)

        val slowConsumers = consumerTrackingValues.collect {
          case t
              if t.firehoseOnly &&
              isDurationGreaterThan(t.timestamp, fastestConsumer.timestamp, settings.slowConsumerBehindThreshold) =>
            t.consumerId -> t
        }.toMap

        val changedConsumerTrackingValues = consumerTrackingValues.flatMap { tracking =>
          if (slowConsumers.contains(tracking.consumerId)) {
            if (tracking.slowConsumerCandidate.isDefined)
              None // keep original
            else
              Some(tracking.copy(slowConsumerCandidate = Some(now)))
          } else if (tracking.slowConsumerCandidate.isDefined) {
            Some(tracking.copy(slowConsumerCandidate = None)) // not slow any more
          } else {
            None
          }
        }

        changedConsumerTrackingValues.foreach { tracking =>
          consumerTracking.merge(
            tracking.consumerId,
            tracking,
            (existing, _) => existing.copy(slowConsumerCandidate = tracking.slowConsumerCandidate))
        }

        val newConsumerTrackingValues = consumerTracking.values.iterator.asScala.toVector

        // FIXME trace
        if (log.isDebugEnabled && newConsumerTrackingValues.iterator.map(_.timestamp).toSet.size > 1) {
          newConsumerTrackingValues.foreach { tracking =>
            val diffFastest = fastestConsumer.timestamp.toEpochMilli - tracking.timestamp.toEpochMilli
            val diffFastestStr =
              if (diffFastest > 0) s"behind fastest [$diffFastest] ms"
              else if (diffFastest < 0) s"ahead of fastest [$diffFastest] ms" // not possible
              else "same as fastest"
            val diffSlowest = slowestConsumer.timestamp.toEpochMilli - tracking.timestamp.toEpochMilli
            val diffSlowestStr =
              if (diffSlowest > 0) s"behind slowest [$diffSlowest] ms" // not possible
              else if (diffSlowest < 0) s"ahead of slowest [${-diffSlowest}] ms"
              else "same as slowest"
            log.debug(
              s"Firehose entityType [$entityType] sliceRange [$sliceRange] consumer [${tracking.consumerId}], " +
              s"$diffFastestStr, $diffSlowestStr, firehoseOnly [${tracking.firehoseOnly}]")
          }
        }

        val firehoseConsumerCount = newConsumerTrackingValues.count(_.firehoseOnly)
        val confirmedSlowConsumers = newConsumerTrackingValues.filter { tracking =>
          tracking.slowConsumerCandidate match {
            case None => false
            case Some(detectedTimestamp) =>
              isDurationGreaterThan(detectedTimestamp, now, settings.abortSlowConsumerAfter)
          }
        }

        // FIXME is confirmedSlowConsumers.size < firehoseConsumerCount needed? The idea was to not abort if all are slow.
        if (confirmedSlowConsumers.nonEmpty && confirmedSlowConsumers.size < firehoseConsumerCount) {
          if (log.isInfoEnabled) {
            val behind = JDuration
              .between(slowConsumers.valuesIterator.maxBy(_.timestamp).timestamp, fastestConsumer.timestamp)
              .toMillis
            log.info(
              s"Firehose entityType [$entityType] sliceRange [$sliceRange], [${slowConsumers.size}] " +
              s"slow consumers are aborted [${slowConsumers.keysIterator.mkString(", ")}], " +
              s"behind by at least [$behind] ms. [$firehoseConsumerCount] firehose consumers, " +
              s"[${newConsumerTrackingValues.size - firehoseConsumerCount}] catchup consumers.")
          }

          confirmedSlowConsumers.foreach { tracking =>
            tracking.consumerKillSwitch.abort(
              new SlowConsumerException(s"Consumer [${tracking.consumerId}] is too slow."))
          }
        }
      }
    }

    @tailrec def updateConsumerFirehoseOnly(consumerId: String): Unit = {
      val existingTracking = consumerTracking.get(consumerId)
      val tracking = existingTracking match {
        case null =>
          throw new IllegalStateException(s"Expected existing tracking for consumer [$consumerId]")
        case existing =>
          existing.copy(firehoseOnly = true)
      }

      if (!consumerTracking.replace(consumerId, existingTracking, tracking))
        // concurrent update, try again
        updateConsumerFirehoseOnly(consumerId)
    }

  }

  def timestampOffset(env: EventEnvelope[Any]): TimestampOffset =
    env match {
      case eventEnvelope: EventEnvelope[_] if eventEnvelope.offset.isInstanceOf[TimestampOffset] =>
        eventEnvelope.offset.asInstanceOf[TimestampOffset]
      case _ =>
        throw new IllegalArgumentException(s"Expected TimestampOffset, but was [${env.offset.getClass.getName}]")
    }

  def isDurationGreaterThan(from: Instant, to: Instant, duration: JDuration): Boolean =
    JDuration.between(from, to).compareTo(duration) > 0
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class EventsBySliceFirehose(system: ActorSystem) extends Extension {
  import EventsBySliceFirehose._
  private val log = Logging(system, classOf[EventsBySliceFirehose])
  private val firehoses = new ConcurrentHashMap[FirehoseKey, Firehose]()

  @tailrec final def getFirehose(firehoseKey: FirehoseKey): Firehose = {
    val firehose = firehoses.computeIfAbsent(firehoseKey, key => createFirehose(key))
    if (firehose.isShutdown) {
      // concurrency race condition, but it should be removed
      firehoses.remove(firehoseKey, firehose)
      getFirehose(firehoseKey) // try again
    } else
      firehose
  }

  private def createFirehose(key: FirehoseKey): Firehose = {
    implicit val sys: ActorSystem = system

    log.debug("Create firehose entityType [{}], sliceRange [{}]", key.entityType, key.sliceRange)

    val settings = Settings(sys, key.pluginId)

    val firehoseKillSwitch = KillSwitches.shared("firehoseKillSwitch")

    val firehoseHub: Source[EventEnvelope[Any], NotUsed] =
      underlyingEventsBySlices[Any](
        settings.delegateQueryPluginId,
        key.entityType,
        key.sliceRange.min,
        key.sliceRange.max,
        TimestampOffset(Instant.now(), Map.empty),
        firehose = true)
        .via(firehoseKillSwitch.flow)
        .runWith(BroadcastHub.sink[EventEnvelope[Any]](settings.broadcastBufferSize))

    val firehose = new Firehose(key, settings, firehoseHub, firehoseKillSwitch, log)

    val reaperInterval = settings.slowConsumerReaperInterval
    // var because it is used inside the scheduled block to cancel itself
    var reaperTask: Cancellable = null
    reaperTask = system.scheduler.scheduleWithFixedDelay(reaperInterval, reaperInterval) { () =>
      if (reaperTask == null)
        () // theoretical possibility but would only mean that the first tick is ignored
      else if (firehose.isShutdown)
        reaperTask.cancel()
      else
        firehose.detectSlowConsumers(Instant.now)
    }(system.dispatcher)

    firehose
  }

  def eventsBySlices[Event](
      pluginId: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[EventEnvelope[Event], NotUsed] = {
    val sliceRange = minSlice to maxSlice
    val firehoseKey = FirehoseKey(pluginId, entityType, sliceRange)
    val firehose = getFirehose(firehoseKey)
    val settings = firehose.settings
    val consumerId = UUID.randomUUID().toString

    def consumerTerminated(): Unit = {
      if (firehose.consumerTerminated(consumerId) == 0) {
        // Don't shutdown firehose immediately because Projection it should survive Projection restart
        system.scheduler.scheduleOnce(settings.firehoseLingerTimeout) {
          if (firehose.shutdownFirehoseIfNoConsumers())
            firehoses.remove(firehoseKey)
        }(system.dispatcher)
      }
    }

    val catchupKillSwitch = KillSwitches.shared("catchupKillSwitch")
    val catchupSource =
      underlyingEventsBySlices[Any](
        settings.delegateQueryPluginId,
        entityType,
        minSlice,
        maxSlice,
        offset,
        firehose = false).via(catchupKillSwitch.flow)

    val consumerKillSwitch = KillSwitches.shared("consumerKillSwitch")

    val firehoseSource = firehose.firehoseHub.map { env =>
      // don't look at pub-sub or backtracking events
      if (env.source == "")
        firehose.updateConsumerTracking(consumerId, timestampOffset(env).timestamp, consumerKillSwitch)
      env
    }

    import GraphDSL.Implicits._
    val catchupOrFirehose = GraphDSL.createGraph(catchupSource) { implicit b => r =>
      val merge = b.add(new CatchupOrFirehose(consumerId, firehose, catchupKillSwitch))
      r ~> merge.in1
      FlowShape(merge.in0, merge.out)
    }

    firehoseSource
      .via(catchupOrFirehose)
      .map(_.asInstanceOf[EventEnvelope[Event]])
      .via(consumerKillSwitch.flow)
      .watchTermination()(Keep.right)
      .mapMaterializedValue { termination =>
        firehose.consumerStarted(consumerId, consumerKillSwitch)
        termination.onComplete { _ =>
          consumerTerminated()
        }(system.dispatcher)
        NotUsed
      }
  }

  // can be overridden in tests
  protected def underlyingEventsBySlices[Event](
      pluginId: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset,
      @unused firehose: Boolean): Source[EventEnvelope[Event], NotUsed] = {
    PersistenceQuery(system)
      .readJournalFor[EventsBySliceQuery](pluginId)
      .eventsBySlices(entityType, minSlice, maxSlice, offset)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object CatchupOrFirehose {
  private sealed trait Mode
  private case object CatchUpOnly extends Mode
  private final case class Both(caughtUpTimestamp: Instant) extends Mode
  private case object FirehoseOnly extends Mode
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class CatchupOrFirehose(
    consumerId: String,
    firehose: EventsBySliceFirehose.Firehose,
    catchupKillSwitch: KillSwitch)
    extends GraphStage[FanInShape2[EventEnvelope[Any], EventEnvelope[Any], EventEnvelope[Any]]] {
  import CatchupOrFirehose._
  import EventsBySliceFirehose.isDurationGreaterThan
  import EventsBySliceFirehose.timestampOffset

  override def initialAttributes = Attributes.name("CatchupOrFirehose")
  override val shape: FanInShape2[EventEnvelope[Any], EventEnvelope[Any], EventEnvelope[Any]] =
    new FanInShape2[EventEnvelope[Any], EventEnvelope[Any], EventEnvelope[Any]]("CatchupOrFirehose")
  def out: Outlet[EventEnvelope[Any]] = shape.out
  val firehoseInlet: Inlet[EventEnvelope[Any]] = shape.in0
  val catchupInlet: Inlet[EventEnvelope[Any]] = shape.in1

  private def settings = firehose.settings
  private def entityType = firehose.firehoseKey.entityType
  private def sliceRange = firehose.firehoseKey.sliceRange

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {

      // Without this the completion signalling would take one extra pull
      private def willShutDown: Boolean = isClosed(firehoseInlet)

      private val firehoseHandler = new FirehoseHandler(firehoseInlet)
      private val catchupHandler = new CatchupHandler(catchupInlet)

      private var mode: Mode = CatchUpOnly

      override protected def logSource: Class[_] = classOf[CatchupOrFirehose]

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          tryPushOutput()
          tryPullAllIfNeeded()
        }
      })

      setHandler(firehoseInlet, firehoseHandler)
      setHandler(catchupInlet, catchupHandler)

      private def tryPushOutput(): Unit = {
        def tryPushFirehoseValue(): Boolean =
          firehoseHandler.value match {
            case OptionVal.Some(env) =>
              firehoseHandler.value = OptionVal.None
              if (log.isDebugEnabled) // FIXME trace
                log.debug(
                  s"Firehose entityType [$entityType] sliceRange [$sliceRange] consumer [$consumerId] push from " +
                  s" firehose [${env.persistenceId}] seqNr [${env.sequenceNr}], source [${env.source}]")
              push(out, env)
              true
            case _ =>
              false
          }

        def tryPushCatchupValue(): Boolean =
          catchupHandler.value match {
            case OptionVal.Some(env) =>
              catchupHandler.value = OptionVal.None
              if (log.isDebugEnabled) // FIXME trace
                log.debug(
                  s"Firehose entityType [$entityType] sliceRange [$sliceRange] consumer [$consumerId] push from " +
                  s"catchup [${env.persistenceId}] seqNr [${env.sequenceNr}], source [${env.source}]")
              push(out, env)
              true
            case _ =>
              false
          }

        if (isAvailable(out)) {
          mode match {
            case FirehoseOnly =>
              // there can be one final value from catchup when switching to FirehoseOnly
              if (!tryPushCatchupValue())
                tryPushFirehoseValue()
            case Both(_) =>
              if (!tryPushFirehoseValue())
                tryPushCatchupValue()
            case CatchUpOnly =>
              tryPushCatchupValue()
          }
        }

        if (willShutDown) completeStage()
      }

      private def tryPullAllIfNeeded(): Unit = {
        if (isClosed(firehoseInlet)) {
          completeStage()
        } else {
          if (!hasBeenPulled(firehoseInlet) && firehoseHandler.value.isEmpty) {
            tryPull(firehoseInlet)
          }
          if (mode != FirehoseOnly && !hasBeenPulled(catchupInlet) && catchupHandler.value.isEmpty) {
            tryPull(catchupInlet)
          }
        }
      }

      def isCaughtUp(env: EventEnvelope[Any]): Boolean = {
        if (env.source == "") {
          val offset = timestampOffset(env)
          firehoseHandler.firehoseOffset.timestamp != Instant.EPOCH && !firehoseHandler.firehoseOffset.timestamp
            .isAfter(offset.timestamp)
        } else
          false // don't look at pub-sub or backtracking events
      }

      private class FirehoseHandler(in: Inlet[EventEnvelope[Any]]) extends InHandler {
        var value: OptionVal[EventEnvelope[Any]] = OptionVal.None
        var firehoseOffset: TimestampOffset = TimestampOffset(Instant.EPOCH, Map.empty)

        def updateFirehoseOffset(env: EventEnvelope[Any]): Unit = {
          // don't look at pub-sub or backtracking events
          if (env.source == "") {
            val offset = timestampOffset(env)
            if (offset.timestamp.isAfter(firehoseOffset.timestamp))
              firehoseOffset = offset // update when newer
          }
        }

        override def onPush(): Unit = {
          if (value.isDefined)
            throw new IllegalStateException("FirehoseInlet.onPush but has already value. This is a bug.")

          val env = grab(in)

          mode match {
            case FirehoseOnly =>
              value = OptionVal.Some(env)
            case Both(_) =>
              updateFirehoseOffset(env)
              value = OptionVal.Some(env)
            case CatchUpOnly =>
              updateFirehoseOffset(env)
          }

          tryPushOutput()
          tryPullAllIfNeeded()
        }
      }

      private class CatchupHandler(in: Inlet[EventEnvelope[Any]]) extends InHandler {
        var value: OptionVal[EventEnvelope[Any]] = OptionVal.None

        override def onPush(): Unit = {
          if (value.isDefined)
            throw new IllegalStateException("CatchupInlet.onPush but has already value. This is a bug.")

          val env = grab(in)

          mode match {
            case CatchUpOnly =>
              if (isCaughtUp(env)) {
                val timestamp = timestampOffset(env).timestamp
                log.debug(
                  "Firehose entityType [{}] sliceRange [{}] consumer [{}] caught up at [{}]",
                  entityType,
                  sliceRange,
                  consumerId,
                  timestamp)
                mode = Both(timestamp)
              }
              value = OptionVal.Some(env)

            case Both(caughtUpTimestamp) =>
              // don't look at pub-sub or backtracking events
              if (env.source == "") {
                val timestamp = timestampOffset(env).timestamp
                if (isDurationGreaterThan(caughtUpTimestamp, timestamp, settings.catchupOverlap)) {
                  firehose.updateConsumerFirehoseOnly(consumerId)
                  log.debug(
                    "Firehose entityType [{}] sliceRange [{}] consumer [{}] switching to firehose only [{}]",
                    entityType,
                    sliceRange,
                    consumerId,
                    timestamp)
                  catchupKillSwitch.shutdown()
                  mode = FirehoseOnly
                }
              }

              value = OptionVal.Some(env)

            case FirehoseOnly =>
            // skip
          }

          tryPushOutput()
          tryPullAllIfNeeded()
        }

        override def onUpstreamFinish(): Unit = {
          // important to override onUpstreamFinish, otherwise it will close everything
          log.debug(
            "Firehose entityType [{}] sliceRange [{}] consumer [{}] catchup closed",
            entityType,
            sliceRange,
            consumerId)
        }
      }

    }

  override def toString = "CatchupOrFirehose"
}
