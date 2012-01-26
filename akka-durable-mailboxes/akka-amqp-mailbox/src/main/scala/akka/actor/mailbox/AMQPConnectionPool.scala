/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.ActorSystem
import org.apache.commons.pool._
import org.apache.commons.pool.impl._
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Channel
import org.slf4j.LoggerFactory

private[akka] class AMQPChannelFactory(factory: ConnectionFactory, queue: String) extends PoolableObjectFactory {

  private var connection = factory.newConnection

  private val log = LoggerFactory.getLogger(classOf[AMQPChannelFactory])

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
    channel.queueDeclare(queue, true, false, false, null)
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

class AMQPChannelPool(factory: ConnectionFactory, queue: String) {
  val pool = new StackObjectPool(new AMQPChannelFactory(factory, queue))

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

