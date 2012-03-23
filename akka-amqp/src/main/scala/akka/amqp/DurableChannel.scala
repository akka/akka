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
import akka.dispatch.{Await, Future}

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

  startWith(Unavailable, None)

  when(Unavailable) {
    case Event(RequestChannel(connection), _) ⇒
      cancelTimer("request-channel")
      if (log.isDebugEnabled) log.debug("Requesting channel from %s".format(connection))
      try {
        self ! connection.createChannel
        stay()
      } catch {
        case ioe: IOException ⇒
          log.error(ioe, "Error while requesting channel from connection %s".format(connection))
          setTimer("request-channel", RequestChannel(connection), 5 seconds, true)
      }
    case Event(ConnectionConnected(connection), _) ⇒
      connection ! ConnectionCallback(c ⇒ self ! RequestChannel(c))
      stay()
    case Event(channel: Channel, _) ⇒
      if (log.isDebugEnabled) log.debug("Received channel %s".format(channel))
      channel.addShutdownListener(this)
      if (!registeredCallbacks.isEmpty) {
        if (log.isDebugEnabled) log.debug("Applying %s registered callbacks on channel %s".format(registeredCallbacks.size, channel))
        registeredCallbacks.foreach(_.apply(channel))
      }
      goto(Available) using Some(channel)
    case Event(cause: ShutdownSignalException, _) ⇒
      handleShutdown(cause)
  }

  when(Available) {
    case Event(ConnectionDisconnected(), Some(channel)) ⇒
      log.warning("Connection went down of channel %s".format(channel))
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
        if (log.isDebugEnabled) log.debug("Channel %s shutdown (%s)".format(channel, cause.getMessage))
        stop()
      } else {
        log.error(cause, "Channel %s broke down".format(channel))
        setTimer("request-channel", RequestChannel(channel.getConnection), 1 seconds, true)
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

  def channelActorCreator = if (withDowntimeStash) {
    Props(new DurableChannelActor with DowntimeStash).withDispatcher("akka.amqp.stashing-dispatcher")
  } else {
    Props(new DurableChannelActor)
  }

  private[amqp] val connectionActor = durableConnection.durableConnectionActor
  import akka.pattern.ask
  implicit val timeout = Timeout(5000)
  // copy from internals, so at lease channel actors are children of the connection for supervision purposes
  val channelFuture = (connectionActor.ask(CreateRandomNameChild(channelActorCreator))).map(_.asInstanceOf[ActorRef])
  private[amqp] val channelActor = Await.result(channelFuture, 5 seconds)

  connectionActor ! SubscribeTransitionCallBack(channelActor)

  def onAvailable(callback: Channel ⇒ Unit) {
    channelActor ! RegisterCallback(callback)
  }

  def withChannel[T](callback: Channel ⇒ T): Future[T] = {
    import akka.pattern.ask
    implicit val timeout = Timeout(5000)
    (channelActor.ask(ExecuteCallbackTo(callback))).map(_.asInstanceOf[T])
  }

  def stop() {
    if (!channelActor.isTerminated) {
      channelActor ! ExecuteCallback { channel ⇒
        if (channel.isOpen) {
          if (log.isDebugEnabled) log.debug("Closing channel %s".format(channel))
          Exception.ignoring(classOf[AlreadyClosedException], classOf[ShutdownSignalException]) {
            channel.close()
          }
        }
      }
      channelActor ! PoisonPill
    }
  }
}
