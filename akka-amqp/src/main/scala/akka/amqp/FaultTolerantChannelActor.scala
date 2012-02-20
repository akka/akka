/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

import collection.JavaConversions
import java.lang.Throwable
import akka.actor.Actor
import akka.event.Logging
import akka.pattern.ask
import com.rabbitmq.client.{ ShutdownSignalException, Channel, ShutdownListener }
import scala.PartialFunction
import akka.amqp.AMQP._
import akka.util.duration._
import akka.util.Timeout
import akka.dispatch.Await

abstract private[amqp] class FaultTolerantChannelActor(
  exchangeParameters: Option[ExchangeParameters], channelParameters: Option[ChannelParameters]) extends Actor {

  protected[amqp] var channel: Option[Channel] = None

  implicit val timeout = Timeout(5 seconds)

  val log = Logging(context.system, this)

  override def receive = channelMessageHandler orElse specificMessageHandler

  // to be defined in subclassing actor
  def specificMessageHandler: PartialFunction[Any, Unit]

  private def channelMessageHandler: PartialFunction[Any, Unit] = {

    case Start ⇒ {
      // ask the connection for a new channel
      val c = context.parent ? ChannelRequest
      val newChannel = Await.result(c, timeout.duration).asInstanceOf[Option[Channel]]
      newChannel.foreach(ch ⇒ setupChannelInternal(ch))
    }

    case ch: Channel ⇒ {
      setupChannelInternal(ch)
    }

    case ChannelShutdown(cause) ⇒ {
      closeChannel
      if (cause.isHardError) {
        // connection error
        if (cause.isInitiatedByApplication) {
          log.info("%s got normal shutdown" format toString)
        } else {
          log.error(cause, "%s got hard error" format toString)
        }
      } else {
        // channel error
        log.error(cause, "%s self restarting because of channel shutdown" format toString)
        notifyCallback(Restarting)
        self ! Start
      }
    }

    case Failure(cause) ⇒
      log.error(cause, "%s self restarting because of channel failure" format toString)
      closeChannel
      notifyCallback(Restarting)
      self ! Start
  }

  // to be defined in subclassing actor
  protected def setupChannel(ch: Channel)

  private def setupChannelInternal(ch: Channel) = {
    if (channel.isEmpty) {
      //log.info("%s setting up channel" format toString)
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

      ch.addShutdownListener(new ShutdownListener {
        def shutdownCompleted(cause: ShutdownSignalException) = {
          self ! ChannelShutdown(cause)
        }
      })

      channelParameters.foreach(_.shutdownListener.foreach(sdl ⇒ ch.getConnection.addShutdownListener(sdl)))

      setupChannel(ch)
      channel = Some(ch)
      notifyCallback(Started)
    } else {
      // close not needed channel
      if (ch.isOpen) ch.close()
    }
  }

  private def closeChannel = {
    channel.foreach {
      ch ⇒
        if (ch.isOpen) ch.close
        notifyCallback(Stopped)
        log.info("%s channel closed" format toString)
    }
    channel = None
  }

  private def notifyCallback(message: AMQPMessage) = {
    channelParameters.foreach(_.channelCallback.foreach(cb ⇒ if (!cb.isTerminated) cb ! message))
  }

  override def postRestart(reason: Throwable) {
    self ! Start
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    notifyCallback(Restarting)
    closeChannel
  }

  override def postStop = closeChannel
}
