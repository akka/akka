/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import collection.JavaConversions
import java.lang.Throwable
import akka.event.Logging
import akka.pattern.{ ask, pipe }
import com.rabbitmq.client.{ ShutdownSignalException, Channel, ShutdownListener }
import scala.PartialFunction
import akka.dispatch.{ Promise, ExecutionContext, Future }
import akka.util.{ NonFatal, Timeout }
import akka.actor.{ ActorRef, Status, Kill, Actor }

abstract private[amqp] class FaultTolerantChannelActor(
  exchangeParameters: Option[ExchangeParameters], channelParameters: Option[ChannelParameters]) extends Actor {

  protected[amqp] var channel: Option[Future[Channel]] = None

  val settings = AMQP(context.system)
  implicit val timeout = Timeout(settings.Timeout)

  val log = Logging(context.system, this)

  val shutdownListener = {
    val replyTo = self
    new ShutdownListener { def shutdownCompleted(cause: ShutdownSignalException): Unit = replyTo ! ChannelShutdown(cause) }
  }

  /**
   * handle channel core and custom messages
   */
  override def receive = channelMessageHandler orElse specificMessageHandler

  /**
   * extending actors should implement custom message handling logic in their specificMessageHandler method
   */
  def specificMessageHandler: PartialFunction[Any, Unit]

  /**
   * defines the core channel message handlers.
   */
  private def channelMessageHandler: PartialFunction[Any, Unit] = {

    /**
     * a producer or consumer is requesting a channel, either because they are initially starting, or
     * because the channel has unexpectedly shut down.  the parent is the connection actor associated
     * with this producer/consumer.  it sends back a Future[Channel] or Failure if it can't be established.
     */
    case Start ⇒
      val slf = context.self
      val snd = context.sender

      context.parent ? ChannelRequest onComplete {
        case Right(r) ⇒ slf ? r onComplete {
          case Right(r) ⇒ snd ! r
          case Left(f)  ⇒ snd ! Status.Failure(f)
        }
        case Left(f) ⇒ slf ! Status.Failure(f)
      }
    case ch: Channel ⇒
      setupChannelInternal(ch)
      sender ! self

    /**
     * Someone requested a channel shutdown.  We'll close the channel regardless of the cause, but if
     * the cause was a channel error (isHardError == false), then we're going to try to restart it.  In
     * all other cases, it will stay down.
     */
    case ChannelShutdown(cause) ⇒
      closeChannel
      if (!cause.isHardError) {
        if (cause.isInitiatedByApplication) {
          log.info("{} got normal shutdown", self)
          context stop self
        } else {
          log.error(cause, "{} self restarting because of channel shutdown", self)
          notifyCallback(Restarting)
          self ! Start
        }
      }

    /**
     * If a consumer gets a non-fatal exception when trying to deliver a message to the defined delivery handler,
     * let's try to recycle the channel.
     */
    case Failure(cause) ⇒
      log.error(cause, "{} self restarting because of channel shutdown", self)
      notifyCallback(Restarting)
      self ! Start
  }

  /**
   * Actors that extend this class should define additional setup logic by implementing setupChannel
   */
  protected def setupChannel(ch: Channel)

  /**
   * configure the basic characteristics of the AMQP channel including the exchange declaration, and shutdown
   * listener. calls setupChannel, sends status update and sets the channel variable.
   */
  private def setupChannelInternal(ch: Channel) = {
    if (channel.isEmpty) {
      exchangeParameters.foreach {
        params ⇒
          import params._
          exchangeDeclaration match {
            case PassiveDeclaration ⇒ ch.exchangeDeclarePassive(exchangeName)
            case ActiveDeclaration(durable, autoDelete, _) ⇒
              ch.exchangeDeclare(exchangeName, exchangeType.toString, durable, autoDelete, JavaConversions.mapAsJavaMap(configurationArguments))
            case NoActionDeclaration ⇒ // ignore
          }
      }

      ch.addShutdownListener(shutdownListener)

      for (cp ← channelParameters; sdl ← cp.shutdownListener) ch.getConnection.addShutdownListener(sdl)
      setupChannel(ch)
      channel = Some(Promise.successful(ch)(context.dispatcher))
      notifyCallback(Started)
    } else {
      // close not needed channel, if the channel is not open, then it should be in the process of restarting.
      if (ch.isOpen) ch.close()
    }
  }

  /**
   * shut down the AMQP channel, notify with the status update, and set the channel variable to None.
   */
  private def closeChannel(): Unit = {
    for (opt ← channel; c ← opt) {
      if (c.isOpen) {
        c.removeShutdownListener(shutdownListener)
        c.close()
      }
      notifyCallback(Stopped)
      log.info("{} channel closed", self)
    }
    channel = None
  }

  /**
   * if there is a channel callback actor registered, and it is running, then forward messages to it.  These are used
   * for clients to get status update messages about the channel actor.
   */
  protected[amqp] def notifyCallback(message: AMQPMessage): Unit =
    for (cp ← channelParameters; cb ← cp.channelCallback if !cb.isTerminated) cb ! message

  /**
   * before restarting the channel actor, send a state update messages and try to cleanly close the channel if there is
   * one open.
   */
  override def preRestart(reason: Throwable, message: Option[Any]) = {
    notifyCallback(Restarting)
    closeChannel
  }

  /**
   * after restarting the channel actor, send the start message to initiate the AMQP channel
   *
   */
  override def postRestart(reason: Throwable) {
    self ! Start
  }

  /**
   * if the channel actor is stopped, shut down the AMQP channel
   */
  override def postStop = {
    notifyCallback(Stopped)
    super.postStop
  }
}
