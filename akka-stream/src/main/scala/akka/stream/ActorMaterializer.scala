/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ ActorContext, ActorRef, ActorRefFactory, ActorSystem, ExtendedActorSystem, Props }
import akka.stream.impl._
import com.typesafe.config.Config

import scala.concurrent.duration._
import akka.japi.function

import scala.util.control.NoStackTrace

object ActorMaterializer {

  /**
   * Scala API: Creates a ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   *
   * The materializer's [[akka.stream.ActorMaterializerSettings]] will be obtained from the
   * configuration of the `context`'s underlying [[akka.actor.ActorSystem]].
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: Option[ActorMaterializerSettings] = None, namePrefix: Option[String] = None, optimizations: Optimizations = Optimizations.none)(implicit context: ActorRefFactory): ActorMaterializer = {
    val system = actorSystemOf(context)

    val settings = materializerSettings getOrElse ActorMaterializerSettings(system)
    apply(settings, namePrefix.getOrElse("flow"), optimizations)(context)
  }

  /**
   * Scala API: Creates a ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: ActorMaterializerSettings, namePrefix: String, optimizations: Optimizations)(implicit context: ActorRefFactory): ActorMaterializer = {
    val haveShutDown = new AtomicBoolean(false)
    val system = actorSystemOf(context)

    new ActorMaterializerImpl(
      system,
      materializerSettings,
      system.dispatchers,
      context.actorOf(StreamSupervisor.props(materializerSettings, haveShutDown).withDispatcher(materializerSettings.dispatcher)),
      haveShutDown,
      FlowNameCounter(system).counter,
      namePrefix,
      optimizations)
  }

  /**
   * Scala API: Creates a ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
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
   * Java API: Creates a ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
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
   * Java API: Creates a ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create(settings: ActorMaterializerSettings, context: ActorRefFactory): ActorMaterializer =
    apply(Option(settings), None)(context)

  /**
   * Java API: Creates a ActorMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
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
      case s: ExtendedActorSystem ⇒ s
      case c: ActorContext        ⇒ c.system
      case null                   ⇒ throw new IllegalArgumentException("ActorRefFactory context must be defined")
      case _ ⇒
        throw new IllegalArgumentException(s"ActorRefFactory context must be a ActorSystem or ActorContext, got [${context.getClass.getName}]")
    }
    system
  }

  /**
   * INTERNAL API
   */
  private[akka] def downcast(materializer: Materializer): ActorMaterializer =
    materializer match { //FIXME this method is going to cause trouble for other Materializer implementations
      case m: ActorMaterializer ⇒ m
      case _ ⇒ throw new IllegalArgumentException(s"required [${classOf[ActorMaterializer].getName}] " +
        s"but got [${materializer.getClass.getName}]")
    }
}

/**
 * A ActorMaterializer takes the list of transformations comprising a
 * [[akka.stream.scaladsl.Flow]] and materializes them in the form of
 * [[org.reactivestreams.Processor]] instances. How transformation
 * steps are split up into asynchronous regions is implementation
 * dependent.
 */
abstract class ActorMaterializer extends Materializer {

  def settings: ActorMaterializerSettings

  def effectiveSettings(opAttr: Attributes): ActorMaterializerSettings

  /**
   * Shuts down this materializer and all the stages that have been materialized through this materializer. After
   * having shut down, this materializer cannot be used again. Any attempt to materialize stages after having
   * shut down will result in an IllegalStateException being thrown at materialization time.
   */
  def shutdown(): Unit

  /**
   * Indicates if the materializer has been shut down.
   */
  def isShutdown: Boolean

  /**
   * INTERNAL API: this might become public later
   */
  private[akka] def actorOf(context: MaterializationContext, props: Props): ActorRef

  /**
   * INTERNAL API
   */
  private[akka] def system: ActorSystem

  /** INTERNAL API */
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
  extends RuntimeException(s"Processor actor [$actor] terminated abruptly") with NoStackTrace

object ActorMaterializerSettings {

  def apply(
    initialInputBufferSize: Int,
    maxInputBufferSize: Int,
    dispatcher: String,
    supervisionDecider: Supervision.Decider,
    subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
    debugLogging: Boolean,
    outputBurstLimit: Int,
    optimizations: Optimizations) =
    new ActorMaterializerSettings(
      initialInputBufferSize, maxInputBufferSize, dispatcher, supervisionDecider, subscriptionTimeoutSettings, debugLogging,
      outputBurstLimit, optimizations)

  /**
   * Create [[ActorMaterializerSettings]].
   *
   * You can refine the configuration based settings using [[ActorMaterializerSettings#withInputBuffer]],
   * [[ActorMaterializerSettings#withDispatcher]]
   */
  def apply(system: ActorSystem): ActorMaterializerSettings =
    apply(system.settings.config.getConfig("akka.stream.materializer"))

  /**
   * Create [[ActorMaterializerSettings]].
   *
   * You can refine the configuration based settings using [[ActorMaterializerSettings#withInputBuffer]],
   * [[ActorMaterializerSettings#withDispatcher]]
   */
  def apply(config: Config): ActorMaterializerSettings =
    ActorMaterializerSettings(
      initialInputBufferSize = config.getInt("initial-input-buffer-size"),
      maxInputBufferSize = config.getInt("max-input-buffer-size"),
      dispatcher = config.getString("dispatcher"),
      supervisionDecider = Supervision.stoppingDecider,
      subscriptionTimeoutSettings = StreamSubscriptionTimeoutSettings(config),
      debugLogging = config.getBoolean("debug-logging"),
      outputBurstLimit = config.getInt("output-burst-limit"),
      optimizations = Optimizations.none)

  /**
   * Java API
   *
   * You can refine the configuration based settings using [[ActorMaterializerSettings#withInputBuffer]],
   * [[ActorMaterializerSettings#withDispatcher]]
   */
  def create(system: ActorSystem): ActorMaterializerSettings =
    apply(system)

  /**
   * Java API
   *
   * You can refine the configuration based settings using [[ActorMaterializerSettings#withInputBuffer]],
   * [[ActorMaterializerSettings#withDispatcher]]
   */
  def create(config: Config): ActorMaterializerSettings =
    apply(config)
}

/**
 * The buffers employed by the generated Processors can be configured by
 * creating an appropriate instance of this class.
 *
 * This will likely be replaced in the future by auto-tuning these values at runtime.
 */
final class ActorMaterializerSettings(
  val initialInputBufferSize: Int,
  val maxInputBufferSize: Int,
  val dispatcher: String,
  val supervisionDecider: Supervision.Decider,
  val subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
  val debugLogging: Boolean,
  val outputBurstLimit: Int,
  val optimizations: Optimizations) {

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")

  requirePowerOfTwo(maxInputBufferSize, "maxInputBufferSize")
  require(initialInputBufferSize <= maxInputBufferSize, s"initialInputBufferSize($initialInputBufferSize) must be <= maxInputBufferSize($maxInputBufferSize)")

  private def copy(
    initialInputBufferSize: Int = this.initialInputBufferSize,
    maxInputBufferSize: Int = this.maxInputBufferSize,
    dispatcher: String = this.dispatcher,
    supervisionDecider: Supervision.Decider = this.supervisionDecider,
    subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings = this.subscriptionTimeoutSettings,
    debugLogging: Boolean = this.debugLogging,
    outputBurstLimit: Int = this.outputBurstLimit,
    optimizations: Optimizations = this.optimizations) =
    new ActorMaterializerSettings(
      initialInputBufferSize, maxInputBufferSize, dispatcher, supervisionDecider, subscriptionTimeoutSettings, debugLogging,
      outputBurstLimit, optimizations)

  def withInputBuffer(initialSize: Int, maxSize: Int): ActorMaterializerSettings =
    copy(initialInputBufferSize = initialSize, maxInputBufferSize = maxSize)

  def withDispatcher(dispatcher: String): ActorMaterializerSettings =
    copy(dispatcher = dispatcher)

  /**
   * Scala API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific flows of the stream operations with
   * [[akka.stream.Attributes#supervisionStrategy]].
   */
  def withSupervisionStrategy(decider: Supervision.Decider): ActorMaterializerSettings =
    copy(supervisionDecider = decider)

  /**
   * Java API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific flows of the stream operations with
   * [[akka.stream.Attributes#supervisionStrategy]].
   */
  def withSupervisionStrategy(decider: function.Function[Throwable, Supervision.Directive]): ActorMaterializerSettings = {
    import Supervision._
    copy(supervisionDecider = decider match {
      case `resumingDecider`   ⇒ resumingDecider
      case `restartingDecider` ⇒ restartingDecider
      case `stoppingDecider`   ⇒ stoppingDecider
      case other               ⇒ other.apply _
    })
  }

  def withDebugLogging(enable: Boolean): ActorMaterializerSettings =
    copy(debugLogging = enable)

  def withOptimizations(optimizations: Optimizations): ActorMaterializerSettings =
    copy(optimizations = optimizations)

  private def requirePowerOfTwo(n: Integer, name: String): Unit = {
    require(n > 0, s"$name must be > 0")
    require((n & (n - 1)) == 0, s"$name must be a power of two")
  }
}

object StreamSubscriptionTimeoutSettings {
  import akka.stream.StreamSubscriptionTimeoutTerminationMode._

  def apply(mode: StreamSubscriptionTimeoutTerminationMode, timeout: FiniteDuration): StreamSubscriptionTimeoutSettings =
    new StreamSubscriptionTimeoutSettings(mode, timeout)

  /** Java API */
  def create(config: Config): StreamSubscriptionTimeoutSettings =
    apply(config)

  def apply(config: Config): StreamSubscriptionTimeoutSettings = {
    val c = config.getConfig("subscription-timeout")
    StreamSubscriptionTimeoutSettings(
      mode = c.getString("mode").toLowerCase(Locale.ROOT) match {
        case "no" | "off" | "false" | "noop" ⇒ NoopTermination
        case "warn"                          ⇒ WarnTermination
        case "cancel"                        ⇒ CancelTermination
      },
      timeout = c.getDuration("timeout", TimeUnit.MILLISECONDS).millis)
  }
}

final class StreamSubscriptionTimeoutSettings(val mode: StreamSubscriptionTimeoutTerminationMode, val timeout: FiniteDuration)

sealed abstract class StreamSubscriptionTimeoutTerminationMode

object StreamSubscriptionTimeoutTerminationMode {
  case object NoopTermination extends StreamSubscriptionTimeoutTerminationMode
  case object WarnTermination extends StreamSubscriptionTimeoutTerminationMode
  case object CancelTermination extends StreamSubscriptionTimeoutTerminationMode

  /** Java API */
  def noop = NoopTermination
  /** Java API */
  def warn = WarnTermination
  /** Java API */
  def cancel = CancelTermination

}

final object Optimizations {
  val none: Optimizations = Optimizations(collapsing = false, elision = false, simplification = false, fusion = false)
  val all: Optimizations = Optimizations(collapsing = true, elision = true, simplification = true, fusion = true)
}

final case class Optimizations(collapsing: Boolean, elision: Boolean, simplification: Boolean, fusion: Boolean) {
  def isEnabled: Boolean = collapsing || elision || simplification || fusion
}
