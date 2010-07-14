/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.amqp.AMQP.{ChannelParameters, ExchangeParameters}

import com.rabbitmq.client.{Channel, RpcClient}

class RpcClientActor(exchangeParameters: ExchangeParameters,
                     routingKey: String,
                     inSerializer: Serializer,
                     outSerializer: Serializer,
                     channelParameters: Option[ChannelParameters] = None) extends FaultTolerantChannelActor(exchangeParameters, channelParameters) {

  import exchangeParameters._

  var rpcClient: Option[RpcClient] = None

  log.info("%s started", this)

  def specificMessageHandler = {
    case payload: AnyRef =>
      rpcClient match {
        case Some(client) =>
          val response: Array[Byte] = client.primitiveCall(inSerializer.toBinary(payload))
          self.reply(outSerializer.fromBinary(response, None))
        case None => error("%s has no client to send messages with".format(this))
      }
  }

  protected def setupChannel(ch: Channel) = rpcClient = Some(new RpcClient(ch, exchangeName, routingKey))

  override def preRestart(reason: Throwable) = {
    rpcClient = None
    super.preRestart(reason)
  }

  override def toString = "AMQP.RpcClient[exchange=" +exchangeName + ", routingKey=" + routingKey+ "]"
}