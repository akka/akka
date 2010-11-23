/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

import collection.JavaConversions
import java.lang.Throwable
import akka.actor.Actor
import Actor._
import com.rabbitmq.client.{ShutdownSignalException, Channel, ShutdownListener}
import scala.PartialFunction
import akka.amqp.AMQP._

abstract private[amqp] class FaultTolerantChannelActor(
        exchangeParameters: Option[ExchangeParameters], channelParameters: Option[ChannelParameters]) extends Actor {
  protected[amqp] var channel: Option[Channel] = None
  log.info("%s is started", toString)

  override def receive = channelMessageHandler orElse specificMessageHandler

  // to be defined in subclassing actor
  def specificMessageHandler: PartialFunction[Any, Unit]

  private def channelMessageHandler: PartialFunction[Any, Unit] = {
    case Start =>
      // ask the connection for a new channel
      self.supervisor.foreach {
        sup =>
          log.info("%s is requesting new channel from supervising connection", toString)
          val newChannel: Option[Option[Channel]] = (sup !! ChannelRequest).as[Option[Channel]]
          newChannel.foreach(ch => ch.foreach(c => setupChannelInternal(c)))
      }
    case ch: Channel => {
      setupChannelInternal(ch)
    }
    case ChannelShutdown(cause) => {
      closeChannel
      if (cause.isHardError) {
        // connection error
        if (cause.isInitiatedByApplication) {
          log.info("%s got normal shutdown", toString)
        } else {
          log.error(cause, "%s got hard error", toString)
        }
      } else {
        // channel error
        log.error(cause, "%s self restarting because of channel shutdown", toString)
        notifyCallback(Restarting)
        self ! Start
      }
    }
    case Failure(cause) =>
      log.error(cause, "%s self restarting because of channel failure", toString)
      closeChannel
      notifyCallback(Restarting)
      self ! Start
  }

  // to be defined in subclassing actor
  protected def setupChannel(ch: Channel)

  private def setupChannelInternal(ch: Channel) = if (channel.isEmpty) {

    exchangeParameters.foreach {
      params =>
        import params._
        exchangeDeclaration match {
          case PassiveDeclaration => ch.exchangeDeclarePassive(exchangeName)
          case ActiveDeclaration(durable, autoDelete, _) =>
            ch.exchangeDeclare(exchangeName, exchangeType.toString, durable, autoDelete, JavaConversions.asJavaMap(configurationArguments))
          case NoActionDeclaration => // ignore
        }
    }
    ch.addShutdownListener(new ShutdownListener {
      def shutdownCompleted(cause: ShutdownSignalException) = {
        self ! ChannelShutdown(cause)
      }
    })
    channelParameters.foreach(_.shutdownListener.foreach(sdl => ch.getConnection.addShutdownListener(sdl)))

    setupChannel(ch)
    channel = Some(ch)
    notifyCallback(Started)
    log.info("Channel setup for %s", toString)
  }

  private def closeChannel = {
    channel.foreach {
      ch =>
        if (ch.isOpen) ch.close
        notifyCallback(Stopped)
        log.info("%s channel closed", toString)
    }
    channel = None
  }

  private def notifyCallback(message: AMQPMessage) = {
    channelParameters.foreach(_.channelCallback.foreach(cb => if (cb.isRunning) cb ! message))
  }

  override def preRestart(reason: Throwable) = {
    notifyCallback(Restarting)
    closeChannel
  }

  override def postStop = closeChannel
}
