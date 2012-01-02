/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel

import org.apache.camel.model.{RouteDefinition, ProcessorDefinition}

import akka.actor._
import akka.dispatch.Await
import akka.util.{Timeout, Duration}
import java.util.concurrent.TimeoutException
import util.matching.Regex
import org.apache.camel.{Exchange, TypeConverter}
import akka.util.duration._

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer extends Actor{
  import RouteDefinitionHandler._

  protected[this] lazy val camel : ConsumerRegistry = Camel.instance
  def endpointUri : String

  /**
   * The default route definition handler is the identity function
   */
  private[camel] var routeDefinitionHandler: RouteDefinitionHandler = identity

  override def postStop(){ camel.unregisterConsumer(this) }
  override def preStart(){ camel.registerConsumer(endpointUri, this) }

  /**
   * When endpoint is outCapable (can produce responses) outTimeout is the maximum time
   * the endpoint can take to send the response back. It defaults to Int.MaxValue seconds.
   * It can be overwritten by setting @see blocking property
   */
  def outTimeout : Duration = Int.MaxValue seconds

  /**
   * Determines whether two-way communications between an endpoint and this consumer actor
   * should be done in blocking or non-blocking mode (default is non-blocking). This method
   * doesn't have any effect on one-way communications (they'll never block).
   */
  def blocking : BlockingOrNot = NonBlocking

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   */
  def autoack = true

  /**
   * Sets the route definition handler for creating a custom route to this consumer instance.
   */
  def onRouteDefinition(h: RouteDefinition => ProcessorDefinition[_]): Unit = onRouteDefinition(RouteDefinitionHandler.from(h))

  /**
   * Sets the route definition handler for creating a custom route to this consumer instance.
   * <p>
   * Java API.
   */
  def onRouteDefinition(h: RouteDefinitionHandler): Unit = routeDefinitionHandler = h
}

sealed trait BlockingOrNot
case object NonBlocking extends BlockingOrNot
case class Blocking(timeout : Duration) extends BlockingOrNot{
  override def toString = "Blocking(%d nanos)".format(timeout.toNanos)
}

object BlockingOrNot{
  def typeConverter = new TypeConverter{
    import akka.util.duration._
    val blocking = new Regex("Blocking\\((\\d+) nanos\\)")
    def convertTo[T](`type`: Class[T], value: AnyRef) = `type` match{
      case c: Class[BlockingOrNot] => value.toString match  {
        case blocking(timeout) => Blocking(timeout.toLong nanos).asInstanceOf[T]
        case "NonBlocking" => NonBlocking.asInstanceOf[T]
      }
    }

    def convertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
    def mandatoryConvertTo[T](`type`: Class[T], value: AnyRef) = convertTo(`type`, value)
    def mandatoryConvertTo[T](`type`: Class[T], exchange: Exchange, value: AnyRef) = convertTo(`type`, value)
  }
}

/**
 *  Java-friendly Consumer.
 *
 * @see UntypedConsumerActor
 * @see RemoteUntypedConsumerActor
 *
 * @author Martin Krasser
 */
trait UntypedConsumer extends Consumer { self: UntypedActor =>
  final def endpointUri = getEndpointUri
  final override def blocking = isBlocking
  final override def autoack = isAutoack

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

  /**
   * Determines whether two-way communications between an endpoint and this consumer actor
   * should be done in blocking or non-blocking mode (default is non-blocking). This method
   * doesn't have any effect on one-way communications (they'll never block).
   */
  def isBlocking() = super.blocking

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   */
  def isAutoack() = super.autoack
}

/**
 * Subclass this abstract class to create an MDB-style untyped consumer actor. This
 * class is meant to be used from Java.
 */
abstract class UntypedConsumerActor extends UntypedActor with UntypedConsumer

/**
 * A callback handler for route definitions to consumer actors.
 *
 * @author Martin Krasser
 */
trait RouteDefinitionHandler {
  def onRouteDefinition(rd: RouteDefinition): ProcessorDefinition[_]
}

/**
 * The identity route definition handler.
 *
 * @author Martin Krasser
 *
 */
class RouteDefinitionIdentity extends RouteDefinitionHandler {
  def onRouteDefinition(rd: RouteDefinition) = rd
}

/**
 * @author Martin Krasser
 */
object RouteDefinitionHandler {
  /**
   * Returns the identity route definition handler
   */
  val identity = new RouteDefinitionIdentity

  /**
   * Created a route definition handler from the given function.
   */
  def from(f: RouteDefinition => ProcessorDefinition[_]) = new RouteDefinitionHandler {
    def onRouteDefinition(rd: RouteDefinition) = f(rd)
  }
}

object ActivationAware{

  /**
   * Awaits for actor to be activated.
   */
  def awaitActivation(actor: ActorRef, timeout: Duration) = {
    implicit val timeout2 = Timeout(timeout)
    try{
      Await.ready(actor ? AwaitActivation, timeout)
    }catch {
      case e: TimeoutException => throw new ActivationTimeoutException
    }
  }
}

class ActivationTimeoutException extends RuntimeException("Timed out while waiting for activation. Please make sure your actor extends ActivationAware trait.")

trait ActivationAware extends Actor{
  private[this] var awaiting : List[ActorRef] = Nil
  private[this] var activated = false


  override def preStart {
    super.preStart()
    context.become(activation orElse receive, true)
  }

  def activation : Receive = {
    case AwaitActivation => if (activated) sender ! EndpointActivated else awaiting ::= sender
    case EndpointActivated => {
      migration.Migration.EventHandler.debug(this+" activated")
      activated = true
      awaiting.foreach(_ ! EndpointActivated)
      awaiting = Nil
    }
  }
}

class ConsumerRequiresFromEndpointException extends RuntimeException("Consumer needs to provide from endpoint. Please make sure the consumer calls method from(\"some uri\") in the body of constructor.")