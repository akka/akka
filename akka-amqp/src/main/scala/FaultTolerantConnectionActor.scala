/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import java.util.{TimerTask, Timer}
import java.io.IOException
import se.scalablesolutions.akka.util.Logging
import com.rabbitmq.client._
import se.scalablesolutions.akka.amqp.AMQP.ConnectionParameters
import se.scalablesolutions.akka.actor.{Exit, Actor}
import se.scalablesolutions.akka.config.ScalaConfig.{Permanent, LifeCycle}

private[amqp] class FaultTolerantConnectionActor(connectionParameters: ConnectionParameters) extends Actor with Logging {
  import connectionParameters._

  self.id = "amqp-connection-%s".format(host)
  self.lifeCycle = Some(LifeCycle(Permanent))

  val reconnectionTimer = new Timer("%s-timer".format(self.id))

  val connectionFactory: ConnectionFactory = new ConnectionFactory()
  connectionFactory.setHost(host)
  connectionFactory.setPort(port)
  connectionFactory.setUsername(username)
  connectionFactory.setPassword(password)
  connectionFactory.setVirtualHost(virtualHost)

  var connection: Option[Connection] = None

  protected def receive = {
    case Connect => connect
    case ChannelRequest => {
      connection match {
        case Some(conn) => {
          val chanel: Channel = conn.createChannel
          self.reply(Some(chanel))
        }
        case None => {
          log.warning("Unable to create new channel - no connection")
          reply(None)
        }
      }
    }
    case ConnectionShutdown(cause) => {
      if (cause.isHardError) {
        // connection error
        if (cause.isInitiatedByApplication) {
          log.info("ConnectionShutdown by application [%s]", self.id)
        } else {
          log.error(cause, "ConnectionShutdown is hard error - self terminating")
          self ! new Exit(self, cause)
        }
      }
    }
  }

  private def connect = if (connection.isEmpty || !connection.get.isOpen) {

    try {
      connection = Some(connectionFactory.newConnection)
      connection.foreach {
        conn =>
          conn.addShutdownListener(new ShutdownListener {
            def shutdownCompleted(cause: ShutdownSignalException) = {
              self ! ConnectionShutdown(cause)
            }
          })
          log.info("Successfully (re)connected to AMQP Server %s:%s [%s]", host, port, self.id)
          log.debug("Sending new channel to %d already linked actors", self.linkedActorsAsList.size)
          self.linkedActorsAsList.foreach(_ ! conn.createChannel)
          notifyCallback(Connected)
      }
    } catch {
      case e: Exception =>
        connection = None
        log.info("Trying to connect to AMQP server in %d milliseconds [%s]"
          , connectionParameters.initReconnectDelay, self.id)
        reconnectionTimer.schedule(new TimerTask() {
          override def run = {
            notifyCallback(Reconnecting)
            self ! Connect
          }
        }, connectionParameters.initReconnectDelay)
    }
  }

  private def disconnect = {
    try {
      connection.foreach(_.close)
      log.debug("Disconnected AMQP connection at %s:%s [%s]", host, port, self.id)
      notifyCallback(Disconnected)
    } catch {
      case e: IOException => log.error("Could not close AMQP connection %s:%s [%s]", host, port, self.id)
      case _ => ()
    }
    connection = None
  }

  private def notifyCallback(message: AMQPMessage) = {
    connectionCallback.foreach(cb => if (cb.isRunning) cb ! message)
  }

  override def shutdown = {
    reconnectionTimer.cancel
    // make sure shutdown is called on all linked actors so they can do channel cleanup before connection is killed
    self.linkedActorsAsList.foreach(_.stop)
    disconnect
  }

  override def preRestart(reason: Throwable) = disconnect

  override def postRestart(reason: Throwable) = {
    notifyCallback(Reconnecting)
    connect
  }

}
