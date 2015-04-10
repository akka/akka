/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.util.Locale
import java.util.concurrent.TimeUnit
import akka.actor.{ ActorContext, ActorRef, ActorRefFactory, ActorSystem, ExtendedActorSystem, Props }
import akka.stream.impl._
import akka.stream.scaladsl.RunnableFlow
import com.typesafe.config.Config
import scala.concurrent.duration._
import akka.actor.Props
import akka.actor.ActorRef
import akka.stream.javadsl.japi
import scala.concurrent.ExecutionContextExecutor

object ActorFlowMaterializer {

  /**
   * Scala API: Creates a ActorFlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   *
   * The materializer's [[akka.stream.ActorFlowMaterializerSettings]] will be obtained from the
   * configuration of the `context`'s underlying [[akka.actor.ActorSystem]].
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: Option[ActorFlowMaterializerSettings] = None, namePrefix: Option[String] = None, optimizations: Optimizations = Optimizations.none)(implicit context: ActorRefFactory): ActorFlowMaterializer = {
    val system = actorSystemOf(context)

    val settings = materializerSettings getOrElse ActorFlowMaterializerSettings(system)
    apply(settings, namePrefix.getOrElse("flow"), optimizations)(context)
  }

  /**
   * Scala API: Creates a ActorFlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: ActorFlowMaterializerSettings, namePrefix: String, optimizations: Optimizations)(implicit context: ActorRefFactory): ActorFlowMaterializer = {
    val system = actorSystemOf(context)

    new ActorFlowMaterializerImpl(
      materializerSettings,
      system.dispatchers,
      context.actorOf(StreamSupervisor.props(materializerSettings).withDispatcher(materializerSettings.dispatcher)),
      FlowNameCounter(system).counter,
      namePrefix,
      optimizations)
  }

  /**
   * Scala API: Creates a ActorFlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: ActorFlowMaterializerSettings)(implicit context: ActorRefFactory): ActorFlowMaterializer =
    apply(Some(materializerSettings), None)

  /**
   * Java API: Creates a ActorFlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * Defaults the actor name prefix used to name actors running the processing steps to `"flow"`.
   * The actor names are built up of `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(context: ActorRefFactory): ActorFlowMaterializer =
    apply()(context)

  /**
   * Java API: Creates a ActorFlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create(settings: ActorFlowMaterializerSettings, context: ActorRefFactory): ActorFlowMaterializer =
    apply(Option(settings), None)(context)

  /**
   * Java API: Creates a ActorFlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(settings: ActorFlowMaterializerSettings, context: ActorRefFactory, namePrefix: String): ActorFlowMaterializer =
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
  private[akka] def downcast(materializer: FlowMaterializer): ActorFlowMaterializer =
    materializer match {
      case m: ActorFlowMaterializer ⇒ m
      case _ ⇒ throw new IllegalArgumentException(s"required [${classOf[ActorFlowMaterializer].getName}] " +
        s"but got [${materializer.getClass.getName}]")
    }

}

/**
 * A ActorFlowMaterializer takes the list of transformations comprising a
 * [[akka.stream.scaladsl.Flow]] and materializes them in the form of
 * [[org.reactivestreams.Processor]] instances. How transformation
 * steps are split up into asynchronous regions is implementation
 * dependent.
 */
abstract class ActorFlowMaterializer extends FlowMaterializer {

  def settings: ActorFlowMaterializerSettings

  def effectiveSettings(opAttr: OperationAttributes): ActorFlowMaterializerSettings

  /**
   * INTERNAL API: this might become public later
   */
  private[akka] def actorOf(context: MaterializationContext, props: Props): ActorRef

}

/**
 * This exception or subtypes thereof should be used to signal materialization
 * failures.
 */
class MaterializationException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

object ActorFlowMaterializerSettings {
  /**
   * Create [[ActorFlowMaterializerSettings]].
   *
   * You can refine the configuration based settings using [[ActorFlowMaterializerSettings#withInputBuffer]],
   * [[ActorFlowMaterializerSettings#withDispatcher]]
   */
  def apply(system: ActorSystem): ActorFlowMaterializerSettings =
    apply(system.settings.config.getConfig("akka.stream.materializer"))

  /**
   * Create [[ActorFlowMaterializerSettings]].
   *
   * You can refine the configuration based settings using [[ActorFlowMaterializerSettings#withInputBuffer]],
   * [[ActorFlowMaterializerSettings#withDispatcher]]
   */
  def apply(config: Config): ActorFlowMaterializerSettings =
    ActorFlowMaterializerSettings(
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
   * You can refine the configuration based settings using [[ActorFlowMaterializerSettings#withInputBuffer]],
   * [[ActorFlowMaterializerSettings#withDispatcher]]
   */
  def create(system: ActorSystem): ActorFlowMaterializerSettings =
    apply(system)

  /**
   * Java API
   *
   * You can refine the configuration based settings using [[ActorFlowMaterializerSettings#withInputBuffer]],
   * [[ActorFlowMaterializerSettings#withDispatcher]]
   */
  def create(config: Config): ActorFlowMaterializerSettings =
    apply(config)
}

/**
 * The buffers employed by the generated Processors can be configured by
 * creating an appropriate instance of this class.
 *
 * This will likely be replaced in the future by auto-tuning these values at runtime.
 */
final case class ActorFlowMaterializerSettings(
  initialInputBufferSize: Int,
  maxInputBufferSize: Int,
  dispatcher: String,
  supervisionDecider: Supervision.Decider,
  subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
  debugLogging: Boolean,
  outputBurstLimit: Int,
  optimizations: Optimizations) {

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")

  requirePowerOfTwo(maxInputBufferSize, "maxInputBufferSize")
  require(initialInputBufferSize <= maxInputBufferSize, s"initialInputBufferSize($initialInputBufferSize) must be <= maxInputBufferSize($maxInputBufferSize)")

  def withInputBuffer(initialSize: Int, maxSize: Int): ActorFlowMaterializerSettings =
    copy(initialInputBufferSize = initialSize, maxInputBufferSize = maxSize)

  def withDispatcher(dispatcher: String): ActorFlowMaterializerSettings =
    copy(dispatcher = dispatcher)

  /**
   * Scala API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific flows of the stream operations with
   * [[akka.stream.scaladsl.OperationAttributes#supervisionStrategy]].
   */
  def withSupervisionStrategy(decider: Supervision.Decider): ActorFlowMaterializerSettings =
    copy(supervisionDecider = decider)

  /**
   * Java API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific flows of the stream operations with
   * [[akka.stream.javadsl.OperationAttributes#supervisionStrategy]].
   */
  def withSupervisionStrategy(decider: japi.Function[Throwable, Supervision.Directive]): ActorFlowMaterializerSettings = {
    import Supervision._
    copy(supervisionDecider = decider match {
      case `resumingDecider`   ⇒ resumingDecider
      case `restartingDecider` ⇒ restartingDecider
      case `stoppingDecider`   ⇒ stoppingDecider
      case other               ⇒ other.apply _
    })
  }

  def withDebugLogging(enable: Boolean): ActorFlowMaterializerSettings =
    copy(debugLogging = enable)

  def withOptimizations(optimizations: Optimizations): ActorFlowMaterializerSettings =
    copy(optimizations = optimizations)

  private def requirePowerOfTwo(n: Integer, name: String): Unit = {
    require(n > 0, s"$name must be > 0")
    require((n & (n - 1)) == 0, s"$name must be a power of two")
  }
}

object StreamSubscriptionTimeoutSettings {
  import akka.stream.StreamSubscriptionTimeoutTerminationMode._

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
final case class StreamSubscriptionTimeoutSettings(mode: StreamSubscriptionTimeoutTerminationMode, timeout: FiniteDuration)

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
