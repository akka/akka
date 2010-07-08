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
    case payload: AnyRef => {

      rpcClient.foreach {client =>
        val response: Array[Byte] = client.primitiveCall(inSerializer.toBinary(payload))
        reply(outSerializer.fromBinary(response, None))
      }
    }
  }

  protected def setupChannel(ch: Channel) = {
    rpcClient = Some(new RpcClient(ch, exchangeName, routingKey))
  }

  override def toString(): String =
    "AMQP.RpcClient[exchange=" +exchangeName +
            ", routingKey=" + routingKey+ "]"

}