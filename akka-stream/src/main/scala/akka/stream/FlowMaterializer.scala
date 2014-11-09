/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.util.Locale
import java.util.concurrent.TimeUnit

import akka.stream.impl._

import scala.collection.immutable

import akka.actor.ActorContext
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import com.typesafe.config.Config
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

import scala.concurrent.duration._

object FlowMaterializer {

  /**
   * Scala API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   *
   * The materializer's [[akka.stream.MaterializerSettings]] will be obtained from the
   * configuration of the `context`'s underlying [[akka.actor.ActorSystem]].
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: Option[MaterializerSettings] = None, namePrefix: Option[String] = None)(implicit context: ActorRefFactory): FlowMaterializer = {
    val system = actorSystemOf(context)

    val settings = materializerSettings getOrElse MaterializerSettings(system)
    apply(settings, namePrefix.getOrElse("flow"))(context)
  }

  /**
   * Scala API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: MaterializerSettings, namePrefix: String)(implicit context: ActorRefFactory): FlowMaterializer = {
    val system = actorSystemOf(context)

    new ActorBasedFlowMaterializer(
      materializerSettings,
      context.actorOf(StreamSupervisor.props(materializerSettings).withDispatcher(materializerSettings.dispatcher)),
      FlowNameCounter(system).counter,
      namePrefix,
      optimizations = Optimizations.none)
  }

  /**
   * Scala API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def apply(materializerSettings: MaterializerSettings)(implicit context: ActorRefFactory): FlowMaterializer =
    apply(Some(materializerSettings), None)

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * Defaults the actor name prefix used to name actors running the processing steps to `"flow"`.
   * The actor names are built up of `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(context: ActorRefFactory): FlowMaterializer =
    apply()(context)

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create one actor that in turn creates actors for the transformation steps.
   */
  def create(settings: MaterializerSettings, context: ActorRefFactory): FlowMaterializer =
    apply(Option(settings), None)(context)

  /**
   * Java API: Creates a FlowMaterializer which will execute every step of a transformation
   * pipeline within its own [[akka.actor.Actor]]. The required [[akka.actor.ActorRefFactory]]
   * (which can be either an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]])
   * will be used to create these actors, therefore it is *forbidden* to pass this object
   * to another actor if the factory is an ActorContext.
   *
   * The `namePrefix` is used as the first part of the names of the actors running
   * the processing steps. The default `namePrefix` is `"flow"`. The actor names are built up of
   * `namePrefix-flowNumber-flowStepNumber-stepName`.
   */
  def create(settings: MaterializerSettings, context: ActorRefFactory, namePrefix: String): FlowMaterializer =
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
 * A FlowMaterializer takes the list of transformations comprising a
 * [[akka.stream.scaladsl.Flow]] and materializes them in the form of
 * [[org.reactivestreams.Processor]] instances. How transformation
 * steps are split up into asynchronous regions is implementation
 * dependent.
 */
abstract class FlowMaterializer(val settings: MaterializerSettings) {

  /**
   * The `namePrefix` shall be used for deriving the names of processing
   * entities that are created during materialization. This is meant to aid
   * logging and error reporting both during materialization and while the
   * stream is running.
   */
  def withNamePrefix(name: String): FlowMaterializer

  // FIXME this is scaladsl specific
  /**
   * This method interprets the given Flow description and creates the running
   * stream. The result can be highly implementation specific, ranging from
   * local actor chains to remote-deployed processing networks.
   */
  def materialize[In, Out](source: scaladsl.Source[In], sink: scaladsl.Sink[Out], ops: List[Ast.AstNode]): scaladsl.MaterializedMap

  /**
   * Create publishers and subscribers for fan-in and fan-out operations.
   */
  def materializeJunction[In, Out](op: Ast.JunctionAstNode, inputCount: Int, outputCount: Int): (immutable.Seq[Subscriber[In]], immutable.Seq[Publisher[Out]])

}

object MaterializerSettings {
  /**
   * Create [[MaterializerSettings]].
   *
   * You can refine the configuration based settings using [[MaterializerSettings#withInputBuffer]],
   * [[MaterializerSettings#withFanOutBuffer]], [[MaterializerSettings#withDispatcher]]
   */
  def apply(system: ActorSystem): MaterializerSettings =
    apply(system.settings.config.getConfig("akka.stream.materializer"))

  /**
   * Create [[MaterializerSettings]].
   *
   * You can refine the configuration based settings using [[MaterializerSettings#withInputBuffer]],
   * [[MaterializerSettings#withFanOutBuffer]], [[MaterializerSettings#withDispatcher]]
   */
  def apply(config: Config): MaterializerSettings =
    MaterializerSettings(
      config.getInt("initial-input-buffer-size"),
      config.getInt("max-input-buffer-size"),
      config.getInt("initial-fan-out-buffer-size"),
      config.getInt("max-fan-out-buffer-size"),
      config.getString("dispatcher"),
      StreamSubscriptionTimeoutSettings(config),
      config.getString("file-io-dispatcher"))

  /**
   * Java API
   *
   * You can refine the configuration based settings using [[MaterializerSettings#withInputBuffer]],
   * [[MaterializerSettings#withFanOutBuffer]], [[MaterializerSettings#withDispatcher]]
   */
  def create(system: ActorSystem): MaterializerSettings =
    apply(system)

  /**
   * Java API
   *
   * You can refine the configuration based settings using [[MaterializerSettings#withInputBuffer]],
   * [[MaterializerSettings#withFanOutBuffer]], [[MaterializerSettings#withDispatcher]]
   */
  def create(config: Config): MaterializerSettings =
    apply(config)
}

/**
 * This exception or subtypes thereof should be used to signal materialization
 * failures.
 */
class MaterializationException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

/**
 * The buffers employed by the generated Processors can be configured by
 * creating an appropriate instance of this class.
 *
 * This will likely be replaced in the future by auto-tuning these values at runtime.
 */
final case class MaterializerSettings(
  initialInputBufferSize: Int,
  maxInputBufferSize: Int,
  initialFanOutBufferSize: Int,
  maxFanOutBufferSize: Int,
  dispatcher: String,
  subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings,
  fileIODispatcher: String) {

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")

  require(maxInputBufferSize > 0, "maxInputBufferSize must be > 0")
  require(isPowerOfTwo(maxInputBufferSize), "maxInputBufferSize must be a power of two")
  require(initialInputBufferSize <= maxInputBufferSize, s"initialInputBufferSize($initialInputBufferSize) must be <= maxInputBufferSize($maxInputBufferSize)")

  require(initialFanOutBufferSize > 0, "initialFanOutBufferSize must be > 0")

  require(maxFanOutBufferSize > 0, "maxFanOutBufferSize must be > 0")
  require(isPowerOfTwo(maxFanOutBufferSize), "maxFanOutBufferSize must be a power of two")
  require(initialFanOutBufferSize <= maxFanOutBufferSize, s"initialFanOutBufferSize($initialFanOutBufferSize) must be <= maxFanOutBufferSize($maxFanOutBufferSize)")

  def withInputBuffer(initialSize: Int, maxSize: Int): MaterializerSettings =
    copy(initialInputBufferSize = initialSize, maxInputBufferSize = maxSize)

  def withFanOutBuffer(initialSize: Int, maxSize: Int): MaterializerSettings =
    copy(initialFanOutBufferSize = initialSize, maxFanOutBufferSize = maxSize)

  def withDispatcher(dispatcher: String): MaterializerSettings =
    copy(dispatcher = dispatcher)

  private def isPowerOfTwo(n: Integer): Boolean = (n & (n - 1)) == 0
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
