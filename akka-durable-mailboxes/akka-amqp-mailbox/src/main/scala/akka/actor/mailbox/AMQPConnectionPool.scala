/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import org.apache.commons.pool._
import org.apache.commons.pool.impl._
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Channel

private[akka] class AMQPChannelFactory(factory: ConnectionFactory, queue: String) extends PoolableObjectFactory {

  private var connection = factory.newConnection

  def makeObject = {
    try {
      createChannel
    } catch {
      case _ ⇒ {
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

  def destroyObject(rc: Object): Unit = {
    rc.asInstanceOf[Channel].close
  }

  def passivateObject(rc: Object): Unit = {}
  def validateObject(rc: Object) = {
    try {
      rc.asInstanceOf[Channel].basicQos(1)
      true
    } catch {
      case _ ⇒ false
    }
  }

  def activateObject(rc: Object): Unit = {}
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

