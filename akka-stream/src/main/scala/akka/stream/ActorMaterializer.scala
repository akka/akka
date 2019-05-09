/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ ActorContext, ActorRef, ActorRefFactory, ActorSystem, ExtendedActorSystem, Props }
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.util.Helpers.toRootLowerCase
import akka.stream.impl._
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.duration._
import akka.japi.function
import akka.stream.stage.GraphStageLogic

import scala.util.control.NoStackTrace

object ActorMaterializer {

  /**
   * Scala API: Creates an ActorMaterializer that can materialize stream blueprints as running streams.
   *
   * The required [[akka.actor.ActorRefFactory]] (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   *
   * The materializer's [[akka.stream.ActorMaterializerSettings]] will be obtained from the
   * configuration of the `context`'s underlying [[akka.actor.ActorSystem]].
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: Option[ActorMaterializerSettings] = None, namePrefix: Option[String] = None)(
      implicit context: ActorRefFactory): ActorMaterializer = {
    val system = actorSystemOf(context)

    val settings = materializerSettings.getOrElse(ActorMaterializerSettings(system))
    apply(settings, namePrefix.getOrElse("flow"))(context)
  }

  /**
   * Scala API: Creates an ActorMaterializer that can materialize stream blueprints as running streams.
   *
   * The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: ActorMaterializerSettings, namePrefix: String)(
      implicit context: ActorRefFactory): ActorMaterializer = {
    val haveShutDown = new AtomicBoolean(false)
    val system = actorSystemOf(context)

    new PhasedFusingActorMaterializer(
      system,
      materializerSettings,
      system.dispatchers,
      actorOfStreamSupervisor(materializerSettings, context, haveShutDown),
      haveShutDown,
      FlowNames(system).name.copy(namePrefix))
  }

  private def actorOfStreamSupervisor(
      materializerSettings: ActorMaterializerSettings,
      context: ActorRefFactory,
      haveShutDown: AtomicBoolean) = {
    val props = StreamSupervisor.props(materializerSettings, haveShutDown)
    context match {
      case s: ExtendedActorSystem => s.systemActorOf(props, StreamSupervisor.nextName())
      case a: ActorContext        => a.actorOf(props, StreamSupervisor.nextName())
    }
  }

  /**
   * Scala API: Creates an ActorMaterializer that can materialize stream blueprints as running streams.
   *
   * The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: ActorMaterializerSettings)(implicit context: ActorRefFactory): ActorMaterializer =
    apply(Some(materializerSettings), None)

  /**
   * INTERNAL API: Creates the `StreamSupervisor` as a system actor.
   */
  private[akka] def systemMaterializer(
      materializerSettings: ActorMaterializerSettings,
      namePrefix: String,
      system: ExtendedActorSystem): ActorMaterializer = {
    val haveShutDown = new AtomicBoolean(false)
    new PhasedFusingActorMaterializer(
      system,
      materializerSettings,
      system.dispatchers,
      system.systemActorOf(StreamSupervisor.props(materializerSettings, haveShutDown), StreamSupervisor.nextName()),
      haveShutDown,
      FlowNames(system).name.copy(namePrefix))
  }

  /**
   * Java API: Creates an ActorMaterializer that can materialize stream blueprints as running streams.
   *
   * The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * Defaults the actor name prefix used to name actors running the processing steps to `"flow"`.
   * The actor names are built up of `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(context: ActorRefFactory): ActorMaterializer =
    apply()(context)

  /**
   * Java API: Creates an ActorMaterializer that can materialize stream blueprints as running streams.
   *
   * The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create(settings: ActorMaterializerSettings, context: ActorRefFactory): ActorMaterializer =
    apply(Option(settings), None)(context)

  /**
   * Java API: Creates an ActorMaterializer that can materialize stream blueprints as running streams.
   *
   * The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(settings: ActorMaterializerSettings, context: ActorRefFactory, namePrefix: String): ActorMaterializer =
    apply(Option(settings), Option(namePrefix))(context)

  private def actorSystemOf(context: ActorRefFactory): ActorSystem = {
    val system = context match {
      case s: ExtendedActorSystem => s
      case c: ActorContext        => c.system
      case null                   => throw new IllegalArgumentException("ActorRefFactory context must be defined")
      case _ =>
        throw new IllegalArgumentException(
          s"ActorRefFactory context must be an ActorSystem or ActorContext, got [${context.getClass.getName}]")
    }
    system
  }

}

/**
 * INTERNAL API
 */
private[akka] object ActorMaterializerHelper {

  /**
   * INTERNAL API
   */
  private[akka] def downcast(materializer: Materializer): ActorMaterializer =
    materializer match { //FIXME this method is going to cause trouble for other Materializer implementations
      case m: ActorMaterializer => m
      case _ =>
        throw new IllegalArgumentException(
          s"required [${classOf[ActorMaterializer].getName}] " +
          s"but got [${materializer.getClass.getName}]")
    }
}

/**
 * An ActorMaterializer takes a stream blueprint and turns it into a running stream.
 */
abstract class ActorMaterializer extends Materializer with MaterializerLoggingProvider {

  def settings: ActorMaterializerSettings

  /**
   * Shuts down this materializer and all the operators that have been materialized through this materializer. After
   * having shut down, this materializer cannot be used again. Any attempt to materialize operators after having
   * shut down will result in an IllegalStateException being thrown at materialization time.
   */
  def shutdown(): Unit

  /**
   * Indicates if the materializer has been shut down.
   */
  def isShutdown: Boolean

  /**
   * INTERNAL API
   */
  private[akka] def actorOf(context: MaterializationContext, props: Props): ActorRef

  /**
   * INTERNAL API
   */
  def system: ActorSystem

  /**
   * INTERNAL API
   */
  private[akka] def logger: LoggingAdapter

  /**
   * INTERNAL API
   */
  private[akka] def supervisor: ActorRef

}

/**
 * This exception or subtypes thereof should be used to signal materialization
 * failures.
 */
class MaterializationException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

/**
 * This exception signals that an actor implementing a Reactive Streams Subscriber, Publisher or Processor
 * has been terminated without being notified by an onError, onComplete or cancel signal. This usually happens
 * when an ActorSystem is shut down while stream processing actors are still running.
 */
final case class AbruptTerminationException(actor: ActorRef)
    extends RuntimeException(s"Processor actor [$actor] terminated abruptly")
    with NoStackTrace

/**
 * Signal that the operator was abruptly terminated, usually seen as a call to `postStop` of the `GraphStageLogic` without
 * any of the handler callbacks seeing completion or failure from upstream or cancellation from downstream. This can happen when
 * the actor running the graph is killed, which happens when the materializer or actor system is terminated.
 */
final class AbruptStageTerminationException(logic: GraphStageLogic)
    extends RuntimeException(
      s"GraphStage [$logic] terminated abruptly, caused by for example materializer or actor system termination.")
    with NoStackTrace

object ActorMaterializerSettings {

  /**
   * Create [[ActorMaterializerSettings]] from individual settings (Scala).
   */
  @Deprecated
  @deprecated(
    "Create the settings using the apply(system) or apply(config) method, and then modify them using the .with methods.",
    since = "2.5.10")
  def apply(
      initialInputBufferSize: Int,
      maxInputBufferSize: Int,
      dispatcher: String,
      supervisionDecider: Supervision.Decider,
      subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
      debugLogging: Boolean,
      outputBurstLimit: Int,
      fuzzingMode: Boolean,
      autoFusing: Boolean,
      maxFixedBufferSize: Int) = {
    // these sins were committed in the name of bin comp:
    val config = ConfigFactory.defaultReference
    new ActorMaterializerSettings(
      initialInputBufferSize,
      maxInputBufferSize,
      dispatcher,
      supervisionDecider,
      subscriptionTimeoutSettings,
      debugLogging,
      outputBurstLimit,
      fuzzingMode,
      autoFusing,
      maxFixedBufferSize,
      1000,
      IOSettings(tcpWriteBufferSize = 16 * 1024),
      StreamRefSettings(config.getConfig("akka.stream.materializer.stream-ref")),
      config.getString(ActorAttributes.IODispatcher.dispatcher))
  }

  /**
   * Create [[ActorMaterializerSettings]] from the settings of an [[akka.actor.ActorSystem]] (Scala).
   */
  def apply(system: ActorSystem): ActorMaterializerSettings =
    apply(system.settings.config.getConfig("akka.stream.materializer"))

  /**
   * Create [[ActorMaterializerSettings]] from a Config subsection (Scala).
   */
  def apply(config: Config): ActorMaterializerSettings =
    new ActorMaterializerSettings(
      initialInputBufferSize = config.getInt("initial-input-buffer-size"),
      maxInputBufferSize = config.getInt("max-input-buffer-size"),
      dispatcher = config.getString("dispatcher"),
      supervisionDecider = Supervision.stoppingDecider,
      subscriptionTimeoutSettings = StreamSubscriptionTimeoutSettings(config),
      debugLogging = config.getBoolean("debug-logging"),
      outputBurstLimit = config.getInt("output-burst-limit"),
      fuzzingMode = config.getBoolean("debug.fuzzing-mode"),
      autoFusing = config.getBoolean("auto-fusing"),
      maxFixedBufferSize = config.getInt("max-fixed-buffer-size"),
      syncProcessingLimit = config.getInt("sync-processing-limit"),
      ioSettings = IOSettings(config.getConfig("io")),
      streamRefSettings = StreamRefSettings(config.getConfig("stream-ref")),
      blockingIoDispatcher = config.getString("blocking-io-dispatcher"))

  /**
   * Create [[ActorMaterializerSettings]] from individual settings (Java).
   */
  @Deprecated
  @deprecated(
    "Create the settings using the create(system) or create(config) method, and then modify them using the .with methods.",
    since = "2.5.10")
  def create(
      initialInputBufferSize: Int,
      maxInputBufferSize: Int,
      dispatcher: String,
      supervisionDecider: Supervision.Decider,
      subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
      debugLogging: Boolean,
      outputBurstLimit: Int,
      fuzzingMode: Boolean,
      autoFusing: Boolean,
      maxFixedBufferSize: Int) = {
    // these sins were committed in the name of bin comp:
    val config = ConfigFactory.defaultReference
    new ActorMaterializerSettings(
      initialInputBufferSize,
      maxInputBufferSize,
      dispatcher,
      supervisionDecider,
      subscriptionTimeoutSettings,
      debugLogging,
      outputBurstLimit,
      fuzzingMode,
      autoFusing,
      maxFixedBufferSize,
      1000,
      IOSettings(tcpWriteBufferSize = 16 * 1024),
      StreamRefSettings(config.getConfig("akka.stream.materializer.stream-ref")),
      config.getString(ActorAttributes.IODispatcher.dispatcher))
  }

  /**
   * Create [[ActorMaterializerSettings]] from the settings of an [[akka.actor.ActorSystem]] (Java).
   */
  def create(system: ActorSystem): ActorMaterializerSettings =
    apply(system)

  /**
   * Create [[ActorMaterializerSettings]] from a Config subsection (Java).
   */
  def create(config: Config): ActorMaterializerSettings =
    apply(config)

}

/**
 * This class describes the configurable properties of the [[ActorMaterializer]].
 * Please refer to the `withX` methods for descriptions of the individual settings.
 *
 * The constructor is not public API, use create or apply on the [[ActorMaterializerSettings]] companion instead.
 */
final class ActorMaterializerSettings @InternalApi private (
    /*
     * Important note: `initialInputBufferSize`, `maxInputBufferSize`, `dispatcher` and
     * `supervisionDecider` must not be used as values in the materializer, or anything the materializer phases use
     * since these settings allow for overriding using [[Attributes]]. They must always be gotten from the effective
     * attributes.
     */
    val initialInputBufferSize: Int,
    val maxInputBufferSize: Int,
    val dispatcher: String,
    val supervisionDecider: Supervision.Decider,
    val subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
    val debugLogging: Boolean,
    val outputBurstLimit: Int,
    val fuzzingMode: Boolean,
    val autoFusing: Boolean,
    val maxFixedBufferSize: Int,
    val syncProcessingLimit: Int,
    val ioSettings: IOSettings,
    val streamRefSettings: StreamRefSettings,
    val blockingIoDispatcher: String) {

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")
  require(syncProcessingLimit > 0, "syncProcessingLimit must be > 0")

  requirePowerOfTwo(maxInputBufferSize, "maxInputBufferSize")
  require(
    initialInputBufferSize <= maxInputBufferSize,
    s"initialInputBufferSize($initialInputBufferSize) must be <= maxInputBufferSize($maxInputBufferSize)")

  // backwards compatibility when added IOSettings, shouldn't be needed since private, but added to satisfy mima
  @deprecated("Use ActorMaterializerSettings.apply or ActorMaterializerSettings.create instead", "2.5.10")
  def this(
      initialInputBufferSize: Int,
      maxInputBufferSize: Int,
      dispatcher: String,
      supervisionDecider: Supervision.Decider,
      subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
      debugLogging: Boolean,
      outputBurstLimit: Int,
      fuzzingMode: Boolean,
      autoFusing: Boolean,
      maxFixedBufferSize: Int,
      syncProcessingLimit: Int,
      ioSettings: IOSettings) =
    // using config like this is not quite right but the only way to solve backwards comp without hard coding settings
    this(
      initialInputBufferSize,
      maxInputBufferSize,
      dispatcher,
      supervisionDecider,
      subscriptionTimeoutSettings,
      debugLogging,
      outputBurstLimit,
      fuzzingMode,
      autoFusing,
      maxFixedBufferSize,
      syncProcessingLimit,
      ioSettings,
      StreamRefSettings(ConfigFactory.defaultReference().getConfig("akka.stream.materializer.stream-ref")),
      ConfigFactory.defaultReference().getString(ActorAttributes.IODispatcher.dispatcher))

  // backwards compatibility when added IOSettings, shouldn't be needed since private, but added to satisfy mima
  @deprecated("Use ActorMaterializerSettings.apply or ActorMaterializerSettings.create instead", "2.5.10")
  def this(
      initialInputBufferSize: Int,
      maxInputBufferSize: Int,
      dispatcher: String,
      supervisionDecider: Supervision.Decider,
      subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
      debugLogging: Boolean,
      outputBurstLimit: Int,
      fuzzingMode: Boolean,
      autoFusing: Boolean,
      maxFixedBufferSize: Int,
      syncProcessingLimit: Int) =
    // using config like this is not quite right but the only way to solve backwards comp without hard coding settings
    this(
      initialInputBufferSize,
      maxInputBufferSize,
      dispatcher,
      supervisionDecider,
      subscriptionTimeoutSettings,
      debugLogging,
      outputBurstLimit,
      fuzzingMode,
      autoFusing,
      maxFixedBufferSize,
      syncProcessingLimit,
      IOSettings(tcpWriteBufferSize = 16 * 1024),
      StreamRefSettings(ConfigFactory.defaultReference().getConfig("akka.stream.materializer.stream-ref")),
      ConfigFactory.defaultReference().getString(ActorAttributes.IODispatcher.dispatcher))

  // backwards compatibility when added IOSettings, shouldn't be needed since private, but added to satisfy mima
  @deprecated("Use ActorMaterializerSettings.apply or ActorMaterializerSettings.create instead", "2.5.10")
  def this(
      initialInputBufferSize: Int,
      maxInputBufferSize: Int,
      dispatcher: String,
      supervisionDecider: Supervision.Decider,
      subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
      debugLogging: Boolean,
      outputBurstLimit: Int,
      fuzzingMode: Boolean,
      autoFusing: Boolean,
      maxFixedBufferSize: Int) =
    // using config like this is not quite right but the only way to solve backwards comp without hard coding settings
    this(
      initialInputBufferSize,
      maxInputBufferSize,
      dispatcher,
      supervisionDecider,
      subscriptionTimeoutSettings,
      debugLogging,
      outputBurstLimit,
      fuzzingMode,
      autoFusing,
      maxFixedBufferSize,
      1000,
      IOSettings(tcpWriteBufferSize = 16 * 1024),
      StreamRefSettings(ConfigFactory.defaultReference().getConfig("akka.stream.materializer.stream-ref")),
      ConfigFactory.defaultReference().getString(ActorAttributes.IODispatcher.dispatcher))

  private def copy(
      initialInputBufferSize: Int = this.initialInputBufferSize,
      maxInputBufferSize: Int = this.maxInputBufferSize,
      dispatcher: String = this.dispatcher,
      supervisionDecider: Supervision.Decider = this.supervisionDecider,
      subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings = this.subscriptionTimeoutSettings,
      debugLogging: Boolean = this.debugLogging,
      outputBurstLimit: Int = this.outputBurstLimit,
      fuzzingMode: Boolean = this.fuzzingMode,
      autoFusing: Boolean = this.autoFusing,
      maxFixedBufferSize: Int = this.maxFixedBufferSize,
      syncProcessingLimit: Int = this.syncProcessingLimit,
      ioSettings: IOSettings = this.ioSettings,
      streamRefSettings: StreamRefSettings = this.streamRefSettings,
      blockingIoDispatcher: String = this.blockingIoDispatcher) = {
    new ActorMaterializerSettings(
      initialInputBufferSize,
      maxInputBufferSize,
      dispatcher,
      supervisionDecider,
      subscriptionTimeoutSettings,
      debugLogging,
      outputBurstLimit,
      fuzzingMode,
      autoFusing,
      maxFixedBufferSize,
      syncProcessingLimit,
      ioSettings,
      streamRefSettings,
      blockingIoDispatcher)
  }

  /**
   * Each asynchronous piece of a materialized stream topology is executed by one Actor
   * that manages an input buffer for all inlets of its shape. This setting configures
   * the default for initial and maximal input buffer in number of elements for each inlet.
   * This can be overridden for individual parts of the
   * stream topology by using [[akka.stream.Attributes#inputBuffer]].
   *
   * FIXME: this is used for all kinds of buffers, not only the stream actor, some use initial some use max,
   *        document and or fix if it should not be like that. Search for get[Attributes.InputBuffer] to see how it is used
   */
  def withInputBuffer(initialSize: Int, maxSize: Int): ActorMaterializerSettings = {
    if (initialSize == this.initialInputBufferSize && maxSize == this.maxInputBufferSize) this
    else copy(initialInputBufferSize = initialSize, maxInputBufferSize = maxSize)
  }

  /**
   * This setting configures the default dispatcher to be used by streams materialized
   * with the [[ActorMaterializer]]. This can be overridden for individual parts of the
   * stream topology by using [[akka.stream.Attributes#dispatcher]].
   */
  def withDispatcher(dispatcher: String): ActorMaterializerSettings = {
    if (this.dispatcher == dispatcher) this
    else copy(dispatcher = dispatcher)
  }

  /**
   * Scala API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific flows of the stream operations with
   * [[akka.stream.Attributes#supervisionStrategy]].
   *
   * Note that supervision in streams are implemented on a per operator basis and is not supported
   * by every operator.
   */
  def withSupervisionStrategy(decider: Supervision.Decider): ActorMaterializerSettings = {
    if (decider eq this.supervisionDecider) this
    else copy(supervisionDecider = decider)
  }

  /**
   * Java API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific flows of the stream operations with
   * [[akka.stream.Attributes#supervisionStrategy]].
   *
   * Note that supervision in streams are implemented on a per operator basis and is not supported
   * by every operator.
   */
  def withSupervisionStrategy(
      decider: function.Function[Throwable, Supervision.Directive]): ActorMaterializerSettings = {
    import Supervision._
    copy(supervisionDecider = decider match {
      case `resumingDecider`   => resumingDecider
      case `restartingDecider` => restartingDecider
      case `stoppingDecider`   => stoppingDecider
      case other               => other.apply _
    })
  }

  /**
   * Test utility: fuzzing mode means that GraphStage events are not processed
   * in FIFO order within a fused subgraph, but randomized.
   */
  def withFuzzing(enable: Boolean): ActorMaterializerSettings =
    if (enable == this.fuzzingMode) this
    else copy(fuzzingMode = enable)

  /**
   * Maximum number of elements emitted in batch if downstream signals large demand.
   */
  def withOutputBurstLimit(limit: Int): ActorMaterializerSettings =
    if (limit == this.outputBurstLimit) this
    else copy(outputBurstLimit = limit)

  /**
   * Limit for number of messages that can be processed synchronously in stream to substream communication
   */
  def withSyncProcessingLimit(limit: Int): ActorMaterializerSettings =
    if (limit == this.syncProcessingLimit) this
    else copy(syncProcessingLimit = limit)

  /**
   * Enable to log all elements that are dropped due to failures (at DEBUG level).
   */
  def withDebugLogging(enable: Boolean): ActorMaterializerSettings =
    if (enable == this.debugLogging) this
    else copy(debugLogging = enable)

  /**
   * Enable automatic fusing of all graphs that are run. For short-lived streams
   * this may cause an initial runtime overhead, but most of the time fusing is
   * desirable since it reduces the number of Actors that are created.
   */
  @deprecated("Turning off fusing is no longer possible with the traversal based materializer", since = "2.5.0")
  def withAutoFusing(enable: Boolean): ActorMaterializerSettings =
    if (enable == this.autoFusing) this
    else copy(autoFusing = enable)

  /**
   * Configure the maximum buffer size for which a FixedSizeBuffer will be preallocated.
   * This defaults to a large value because it is usually better to fail early when
   * system memory is not sufficient to hold the buffer.
   */
  def withMaxFixedBufferSize(size: Int): ActorMaterializerSettings =
    if (size == this.maxFixedBufferSize) this
    else copy(maxFixedBufferSize = size)

  /**
   * Leaked publishers and subscribers are cleaned up when they are not used within a given
   * deadline, configured by [[StreamSubscriptionTimeoutSettings]].
   */
  def withSubscriptionTimeoutSettings(settings: StreamSubscriptionTimeoutSettings): ActorMaterializerSettings =
    if (settings == this.subscriptionTimeoutSettings) this
    else copy(subscriptionTimeoutSettings = settings)

  def withIOSettings(ioSettings: IOSettings): ActorMaterializerSettings =
    if (ioSettings == this.ioSettings) this
    else copy(ioSettings = ioSettings)

  /** Change settings specific to [[SourceRef]] and [[SinkRef]]. */
  def withStreamRefSettings(streamRefSettings: StreamRefSettings): ActorMaterializerSettings =
    if (streamRefSettings == this.streamRefSettings) this
    else copy(streamRefSettings = streamRefSettings)

  def withBlockingIoDispatcher(newBlockingIoDispatcher: String): ActorMaterializerSettings =
    if (newBlockingIoDispatcher == blockingIoDispatcher) this
    else copy(blockingIoDispatcher = newBlockingIoDispatcher)

  private def requirePowerOfTwo(n: Integer, name: String): Unit = {
    require(n > 0, s"$name must be > 0")
    require((n & (n - 1)) == 0, s"$name must be a power of two")
  }

  override def equals(other: Any): Boolean = other match {
    case s: ActorMaterializerSettings =>
      s.initialInputBufferSize == initialInputBufferSize &&
      s.maxInputBufferSize == maxInputBufferSize &&
      s.dispatcher == dispatcher &&
      s.supervisionDecider == supervisionDecider &&
      s.subscriptionTimeoutSettings == subscriptionTimeoutSettings &&
      s.debugLogging == debugLogging &&
      s.outputBurstLimit == outputBurstLimit &&
      s.syncProcessingLimit == syncProcessingLimit &&
      s.fuzzingMode == fuzzingMode &&
      s.autoFusing == autoFusing &&
      s.ioSettings == ioSettings &&
      s.blockingIoDispatcher == blockingIoDispatcher
    case _ => false
  }

  override def toString: String =
    s"ActorMaterializerSettings($initialInputBufferSize,$maxInputBufferSize," +
    s"$dispatcher,$supervisionDecider,$subscriptionTimeoutSettings,$debugLogging,$outputBurstLimit," +
    s"$syncProcessingLimit,$fuzzingMode,$autoFusing,$ioSettings)"
}

object IOSettings {
  def apply(system: ActorSystem): IOSettings =
    apply(system.settings.config.getConfig("akka.stream.materializer.io"))

  def apply(config: Config): IOSettings =
    new IOSettings(tcpWriteBufferSize = math.min(Int.MaxValue, config.getBytes("tcp.write-buffer-size")).toInt)

  def apply(tcpWriteBufferSize: Int): IOSettings =
    new IOSettings(tcpWriteBufferSize)

  /** Java API */
  def create(config: Config) = apply(config)

  /** Java API */
  def create(system: ActorSystem) = apply(system)

  /** Java API */
  def create(tcpWriteBufferSize: Int): IOSettings =
    apply(tcpWriteBufferSize)
}

final class IOSettings private (val tcpWriteBufferSize: Int) {

  def withTcpWriteBufferSize(value: Int): IOSettings = copy(tcpWriteBufferSize = value)

  private def copy(tcpWriteBufferSize: Int): IOSettings = new IOSettings(tcpWriteBufferSize = tcpWriteBufferSize)

  override def equals(other: Any): Boolean = other match {
    case s: IOSettings => s.tcpWriteBufferSize == tcpWriteBufferSize
    case _             => false
  }

  override def toString =
    s"""IoSettings(${tcpWriteBufferSize})"""
}

object StreamSubscriptionTimeoutSettings {
  import akka.stream.StreamSubscriptionTimeoutTerminationMode._

  /**
   * Create settings from individual values (Java).
   */
  def create(
      mode: StreamSubscriptionTimeoutTerminationMode,
      timeout: FiniteDuration): StreamSubscriptionTimeoutSettings =
    new StreamSubscriptionTimeoutSettings(mode, timeout)

  /**
   * Create settings from individual values (Scala).
   */
  def apply(
      mode: StreamSubscriptionTimeoutTerminationMode,
      timeout: FiniteDuration): StreamSubscriptionTimeoutSettings =
    new StreamSubscriptionTimeoutSettings(mode, timeout)

  /**
   * Create settings from a Config subsection (Java).
   */
  def create(config: Config): StreamSubscriptionTimeoutSettings =
    apply(config)

  /**
   * Create settings from a Config subsection (Scala).
   */
  def apply(config: Config): StreamSubscriptionTimeoutSettings = {
    val c = config.getConfig("subscription-timeout")
    StreamSubscriptionTimeoutSettings(mode = toRootLowerCase(c.getString("mode")) match {
      case "no" | "off" | "false" | "noop" => NoopTermination
      case "warn"                          => WarnTermination
      case "cancel"                        => CancelTermination
    }, timeout = c.getDuration("timeout", TimeUnit.MILLISECONDS).millis)
  }
}

/**
 * Leaked publishers and subscribers are cleaned up when they are not used within a given
 * deadline, configured by [[StreamSubscriptionTimeoutSettings]].
 */
final class StreamSubscriptionTimeoutSettings(
    val mode: StreamSubscriptionTimeoutTerminationMode,
    val timeout: FiniteDuration) {
  override def equals(other: Any): Boolean = other match {
    case s: StreamSubscriptionTimeoutSettings => s.mode == mode && s.timeout == timeout
    case _                                    => false
  }
  override def toString: String = s"StreamSubscriptionTimeoutSettings($mode,$timeout)"
}

/**
 * This mode describes what shall happen when the subscription timeout expires for
 * substream Publishers created by operations like `prefixAndTail`.
 */
sealed abstract class StreamSubscriptionTimeoutTerminationMode

object StreamSubscriptionTimeoutTerminationMode {
  case object NoopTermination extends StreamSubscriptionTimeoutTerminationMode
  case object WarnTermination extends StreamSubscriptionTimeoutTerminationMode
  case object CancelTermination extends StreamSubscriptionTimeoutTerminationMode

  /**
   * Do not do anything when timeout expires.
   */
  def noop: StreamSubscriptionTimeoutTerminationMode = NoopTermination

  /**
   * Log a warning when the timeout expires.
   */
  def warn: StreamSubscriptionTimeoutTerminationMode = WarnTermination

  /**
   * When the timeout expires attach a Subscriber that will immediately cancel its subscription.
   */
  def cancel: StreamSubscriptionTimeoutTerminationMode = CancelTermination

}
