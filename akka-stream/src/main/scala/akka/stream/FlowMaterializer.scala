/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.util.Locale
import java.util.concurrent.TimeUnit
import akka.stream.impl._
import akka.stream.scaladsl.Key
import scala.collection.immutable
import akka.actor.ActorContext
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import com.typesafe.config.Config
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import scala.concurrent.duration._
import akka.actor.Props
import akka.actor.ActorRef
import akka.stream.javadsl.japi

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
  def apply(materializerSettings: Option[ActorFlowMaterializerSettings] = None, namePrefix: Option[String] = None)(implicit context: ActorRefFactory): ActorFlowMaterializer = {
    val system = actorSystemOf(context)

    val settings = materializerSettings getOrElse ActorFlowMaterializerSettings(system)
    apply(settings, namePrefix.getOrElse("flow"))(context)
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
  def apply(materializerSettings: ActorFlowMaterializerSettings, namePrefix: String)(implicit context: ActorRefFactory): ActorFlowMaterializer = {
    val system = actorSystemOf(context)

    new ActorFlowMaterializerImpl(
      materializerSettings,
      system.dispatchers,
      context.actorOf(StreamSupervisor.props(materializerSettings).withDispatcher(materializerSettings.dispatcher)),
      FlowNameCounter(system).counter,
      namePrefix)
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

  /**
   * INTERNAL API
   */
  private[akka] def actorOf(props: Props, name: String): ActorRef

}

abstract class FlowMaterializer {

  /**
   * The `namePrefix` shall be used for deriving the names of processing
   * entities that are created during materialization. This is meant to aid
   * logging and failure reporting both during materialization and while the
   * stream is running.
   */
  def withNamePrefix(name: String): FlowMaterializer

  // FIXME this is scaladsl specific
  /**
   * This method interprets the given Flow description and creates the running
   * stream. The result can be highly implementation specific, ranging from
   * local actor chains to remote-deployed processing networks.
   */
  def materialize[In, Out](source: scaladsl.Source[In], sink: scaladsl.Sink[Out], ops: List[Ast.AstNode], keys: List[Key[_]]): scaladsl.MaterializedMap

  /**
   * Create publishers and subscribers for fan-in and fan-out operations.
   */
  def materializeJunction[In, Out](op: Ast.JunctionAstNode, inputCount: Int, outputCount: Int): (immutable.Seq[Subscriber[In]], immutable.Seq[Publisher[Out]])

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
      fileIODispatcher = config.getString("file-io-dispatcher"),
      debugLogging = config.getBoolean("debug-logging"),
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
  fileIODispatcher: String, // FIXME Why does this exist?!
  debugLogging: Boolean,
  optimizations: Optimizations) {

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")

  require(maxInputBufferSize > 0, "maxInputBufferSize must be > 0")
  require(isPowerOfTwo(maxInputBufferSize), "maxInputBufferSize must be a power of two")
  require(initialInputBufferSize <= maxInputBufferSize, s"initialInputBufferSize($initialInputBufferSize) must be <= maxInputBufferSize($maxInputBufferSize)")

  def withInputBuffer(initialSize: Int, maxSize: Int): ActorFlowMaterializerSettings =
    copy(initialInputBufferSize = initialSize, maxInputBufferSize = maxSize)

  def withDispatcher(dispatcher: String): ActorFlowMaterializerSettings =
    copy(dispatcher = dispatcher)

  /**
   * Scala API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific sections of the stream operations with
   * [[akka.stream.scaladsl.OperationAttributes#supervisionStrategy]].
   */
  def withSupervisionStrategy(decider: Supervision.Decider): ActorFlowMaterializerSettings =
    copy(supervisionDecider = decider)

  /**
   * Java API: Decides how exceptions from application code are to be handled, unless
   * overridden for specific sections of the stream operations with
   * [[akka.stream.javadsl.OperationAttributes#supervisionStrategy]].
   */
  def withSupervisionStrategy(decider: japi.Function[Throwable, Supervision.Directive]): ActorFlowMaterializerSettings =
    copy(supervisionDecider = e ⇒ decider.apply(e))

  def withDebugLogging(enable: Boolean): ActorFlowMaterializerSettings =
    copy(debugLogging = enable)

  def withOptimizations(optimizations: Optimizations): ActorFlowMaterializerSettings =
    copy(optimizations = optimizations)

  private def isPowerOfTwo(n: Integer): Boolean = (n & (n - 1)) == 0 // FIXME this considers 0 a power of 2
}

object StreamSubscriptionTimeoutSettings {
  import StreamSubscriptionTimeoutTerminationMode._

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
