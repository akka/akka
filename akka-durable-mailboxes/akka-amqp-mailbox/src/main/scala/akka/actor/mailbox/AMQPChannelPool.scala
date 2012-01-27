/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.AkkaException
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import org.apache.commons.pool._
import org.apache.commons.pool.impl._
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Channel

class AMQPConnectionException(message: String) extends AkkaException(message)

private[akka] class AMQPChannelFactory(factory: ConnectionFactory, log: LoggingAdapter) extends PoolableObjectFactory {

  private var connection = try {
    factory.newConnection
  } catch {
    case e: java.net.ConnectException ⇒
      throw new AMQPConnectionException("Could not connect to AMQP broker: " + e.getMessage)
  }

  def makeObject(): Object = {
    try {
      createChannel
    } catch {
      case e: java.io.IOException ⇒ {
        log.error("Could not create a channel. Will retry after reconnecting to AMQP Server.", e)
        connection.close
        connection = factory.newConnection
        createChannel
      }
    }
  }

  private def createChannel = {
    val channel = connection.createChannel
    channel.basicQos(1)
    channel
  }

  def destroyObject(channel: Object): Unit = {
    channel.asInstanceOf[Channel].close
  }

  def passivateObject(channel: Object): Unit = {}
  def validateObject(channel: Object) = {
    try {
      channel.asInstanceOf[Channel].basicQos(1)
      true
    } catch {
      case _ ⇒ false
    }
  }

  def activateObject(channel: Object): Unit = {}
}

class AMQPChannelPool(factory: ConnectionFactory, log: LoggingAdapter) {
  val pool = new StackObjectPool(new AMQPChannelFactory(factory, log))

  def withChannel[T](body: Channel ⇒ T) = {
    val channel = pool.borrowObject.asInstanceOf[Channel]
    try {
      body(channel)
    } finally {
      pool.returnObject(channel)
    }
  }

  def close = pool.close
}

