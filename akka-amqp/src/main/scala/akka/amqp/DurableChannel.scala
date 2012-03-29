package akka.amqp

import collection.mutable.ArrayBuffer
import com.rabbitmq.client._
import akka.util.duration._
import akka.actor.FSM.SubscribeTransitionCallBack
import java.io.IOException
import util.control.Exception
import akka.actor._
import akka.util.Timeout
import akka.event.Logging
import akka.dispatch.{ Await, Future }
import akka.pattern.ask
import akka.serialization.SerializationExtension
import akka.amqp.Message._

private sealed trait ChannelState
private case object Available extends ChannelState
private case object Initializing extends ChannelState
private case object Unavailable extends ChannelState

private case class RequestChannel(connection: Connection)

trait DowntimeStash extends Stash {
  this: DurableChannelActor ⇒

  when(Unavailable) {
    case Event(channelCallback: ExecuteCallback, _) ⇒
      stash()
      stay()
  }

  onTransition {
    case (_, Available) ⇒
      unstashAll()
  }
}

private[amqp] class DurableChannelActor
  extends Actor with FSM[ChannelState, Option[Channel]] with ShutdownListener {

  val registeredCallbacks = new ArrayBuffer[Channel ⇒ Unit]
  val settings = AMQP(context.system)
  val serialization = SerializationExtension(context.system)

  startWith(Unavailable, None)

  when(Unavailable) {
    case Event(RequestChannel(connection), _) ⇒
      cancelTimer("request-channel")
      log.debug("Requesting channel from {}", connection)
      try {
        self ! connection.createChannel
        stay()
      } catch {
        case ioe: IOException ⇒
          log.error(ioe, "Error while requesting channel from connection {}", connection)
          setTimer("request-channel", RequestChannel(connection), settings.DefaultChannelReconnectTimeout milliseconds, true)
      }
    case Event(ConnectionConnected(connection), _) ⇒
      connection ! ConnectionCallback(c ⇒ self ! RequestChannel(c))
      stay()
    case Event(channel: Channel, _) ⇒
      log.debug("Received channel {}", channel)
      channel.addShutdownListener(this)
      if (!registeredCallbacks.isEmpty) {
        log.debug("Applying {} registered callbacks on channel {}", registeredCallbacks.size, channel)
        registeredCallbacks.foreach(_.apply(channel))
      }
      goto(Available) using Some(channel)
    case Event(cause: ShutdownSignalException, _) ⇒
      handleShutdown(cause)
  }

  when(Available) {
    case Event(MessageWithExchange(message, exchangeName, false), Some(channel)) ⇒
      log.debug("Publishing on '{}': {}", exchangeName, message)
      import message._
      val reply = serialization.serialize(payload) match {
        case Right(serialized) ⇒
          channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties.getOrElse(null), serialized)
        case Left(exception) ⇒ exception
      }
      stay() replying reply
    case Event(MessageWithExchange(message, exchangeName, true), Some(channel)) ⇒
      log.debug("Publishing confirmed on '{}': {}", exchangeName, message)
      import message._
      val reply = serialization.serialize(payload) match {
        case Right(serialized) ⇒
          val seqNo = channel.getNextPublishSeqNo
          channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties.getOrElse(null), serialized)
          seqNo
        case Left(exception) ⇒ exception
      }
      stay() replying reply

    case Event(ConnectionDisconnected(), Some(channel)) ⇒
      log.warning("Connection went down of channel {}", channel)
      goto(Unavailable) using None
    case Event(ExecuteCallback(callback), Some(channel)) ⇒
      callback.apply(channel)
      stay()
    case Event(ExecuteCallbackTo(callback), Some(channel)) ⇒
      stay() replying callback.apply(channel)
    case Event(cause: ShutdownSignalException, _) ⇒
      handleShutdown(cause)
  }

  whenUnhandled {
    case Event(RegisterCallback(callback), channel) ⇒
      channel.foreach(callback.apply(_))
      registeredCallbacks += callback
      stay()
  }

  def handleShutdown(cause: ShutdownSignalException): State = {
    if (cause.isHardError) { // connection error, await ConnectionDisconnected()
      stay()
    } else { // channel error
      val channel = cause.getReference.asInstanceOf[Channel]
      if (cause.isInitiatedByApplication) {
        log.debug("Channel {} shutdown ({})", channel, cause.getMessage)
        stop()
      } else {
        log.error(cause, "Channel {} broke down", channel)
        setTimer("request-channel", RequestChannel(channel.getConnection), settings.DefaultChannelReconnectTimeout milliseconds, true)
        goto(Unavailable) using None
      }
    }
  }

  def shutdownCompleted(cause: ShutdownSignalException) {
    self ! cause
  }
}

case class RegisterCallback(callback: Channel ⇒ Unit)
case class ExecuteCallback(callback: Channel ⇒ Unit)
case class ExecuteCallbackTo[T](callback: Channel ⇒ T)

class DurableChannel(durableConnection: DurableConnection, withDowntimeStash: Boolean = false) {

  import durableConnection.connectionProperties.system
  private val log = Logging(system, this.getClass)
  val settings = AMQP(system)

  def channelActorCreator = if (withDowntimeStash) {
    Props(new DurableChannelActor with DowntimeStash).withDispatcher("akka.amqp.stashing-dispatcher")
  } else {
    Props(new DurableChannelActor)
  }

  private[amqp] val connectionActor = durableConnection.durableConnectionActor
  implicit val timeout = Timeout(settings.DefaultChannelCreationTimeout)
  // copy from internals, so at lease channel actors are children of the connection for supervision purposes
  val channelFuture = connectionActor ? (CreateRandomNameChild(channelActorCreator)) mapTo manifest[ActorRef]
  private[amqp] val channelActor = Await.result(channelFuture, settings.DefaultChannelCreationTimeout milliseconds)

  connectionActor ! SubscribeTransitionCallBack(channelActor)

  def onAvailable(callback: Channel ⇒ Unit) {
    channelActor ! RegisterCallback(callback)
  }

  def withChannel[T](callback: Channel ⇒ T): Future[T] = {
    implicit val timeout = Timeout(settings.DefaultInteractionTimeout)
    (channelActor.ask(ExecuteCallbackTo(callback))).map(_.asInstanceOf[T])
  }

  def stop() {
    if (!channelActor.isTerminated) {
      channelActor ! ExecuteCallback { channel ⇒
        if (channel.isOpen) {
          log.debug("Closing channel {}", channel)
          Exception.ignoring(classOf[AlreadyClosedException], classOf[ShutdownSignalException]) {
            channel.close()
          }
        }
      }
      channelActor ! PoisonPill
    }
  }
}
