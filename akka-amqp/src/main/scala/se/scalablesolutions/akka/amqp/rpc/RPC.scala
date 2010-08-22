package se.scalablesolutions.akka.amqp.rpc

import se.scalablesolutions.akka.amqp.AMQP._
import com.google.protobuf.Message
import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import Actor._
import se.scalablesolutions.akka.amqp._

object RPC {

  def newRpcClient[O, I](connection: ActorRef,
                         exchangeParameters: ExchangeParameters,
                         routingKey: String,
                         serializer: RpcClientSerializer[O, I],
                         channelParameters: Option[ChannelParameters] = None): ActorRef = {
    val rpcActor: ActorRef = actorOf(new RpcClientActor[O, I](
      exchangeParameters, routingKey, serializer, channelParameters))
    connection.startLink(rpcActor)
    rpcActor ! Start
    rpcActor
  }

  def newRpcServer[I, O](connection: ActorRef,
                         exchangeParameters: ExchangeParameters,
                         routingKey: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: I => O,
                         queueName: Option[String] = None,
                         channelParameters: Option[ChannelParameters] = None): RpcServerHandle = {
    val producer = newProducer(connection, ProducerParameters(
      ExchangeParameters("", ExchangeType.Direct), channelParameters = channelParameters))
    val rpcServer = actorOf(new RpcServerActor[I, O](producer, serializer, requestHandler))
    val consumer = newConsumer(connection, ConsumerParameters(exchangeParameters, routingKey, rpcServer,
      channelParameters = channelParameters, selfAcknowledging = false, queueName = queueName))
    RpcServerHandle(producer, consumer)
  }

  case class RpcServerHandle(producer: ActorRef, consumer: ActorRef) {
    def stop = {
      consumer.stop
      producer.stop
    }
  }

  case class RpcClientSerializer[O, I](toBinary: ToBinary[O], fromBinary: FromBinary[I])

  case class RpcServerSerializer[I, O](fromBinary: FromBinary[I], toBinary: ToBinary[O])


  /**
   * RPC convenience
   */
  class RpcClient[O, I](client: ActorRef){
    def call(request: O, timeout: Long = 5000): Option[I] = {
      (client.!!(request, timeout)).as[I]
    }

    def callAsync(request: O, timeout: Long = 5000)(responseHandler: PartialFunction[Option[I],Unit]) = {
      spawn {
        val result = call(request, timeout)
        responseHandler.apply(result)
      }
    }
    def stop = client.stop
  }

  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchange: String,
          requestHandler: I => O,
          routingKey: Option[String] = None,
          queueName: Option[String] = None,
          durable: Boolean = false,
          autoDelete: Boolean = true)(implicit manifest: Manifest[I]): RpcServerHandle = {

    val serializer = new RpcServerSerializer[I, O](
      new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      }, new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      })

    startServer(connection, exchange, requestHandler, routingKey, queueName, durable, autoDelete, serializer)
  }

  def newProtobufRpcClient[O <: Message, I <: Message](
          connection: ActorRef,
          exchange: String,
          routingKey: Option[String] = None,
          durable: Boolean = false,
          autoDelete: Boolean = true,
          passive: Boolean = true)(implicit manifest: Manifest[I]): RpcClient[O, I] = {


    val serializer = new RpcClientSerializer[O, I](
      new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      }, new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      })

    startClient(connection, exchange, routingKey, durable, autoDelete, passive, serializer)
  }

  def newStringRpcServer(connection: ActorRef,
                        exchange: String,
                        requestHandler: String => String,
                        routingKey: Option[String] = None,
                        queueName: Option[String] = None,
                        durable: Boolean = false,
                        autoDelete: Boolean = true): RpcServerHandle = {

    val serializer = new RpcServerSerializer[String, String](
      new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]): String = {
          new String(bytes)
        }
      }, new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      })

    startServer(connection, exchange, requestHandler, routingKey, queueName, durable, autoDelete, serializer)
  }

  def newStringRpcClient(connection: ActorRef,
                        exchange: String,
                        routingKey: Option[String] = None,
                        durable: Boolean = false,
                        autoDelete: Boolean = true,
                        passive: Boolean = true): RpcClient[String, String] = {


    val serializer = new RpcClientSerializer[String, String](
      new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      }, new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]): String = {
          new String(bytes)
        }
      })

    startClient(connection, exchange, routingKey, durable, autoDelete, passive, serializer)
  }

  private def startClient[O, I](connection: ActorRef,
                                exchange: String,
                                routingKey: Option[String] = None,
                                durable: Boolean = false,
                                autoDelete: Boolean = true,
                                passive: Boolean = true,
                                serializer: RpcClientSerializer[O, I]): RpcClient[O, I] = {

    val exchangeParameters = ExchangeParameters(exchange, ExchangeType.Topic,
      exchangeDurable = durable, exchangeAutoDelete = autoDelete, exchangePassive = passive)
    val rKey = routingKey.getOrElse("%s.request".format(exchange))

    val client = newRpcClient(connection, exchangeParameters, rKey, serializer)
    new RpcClient(client)
  }

  private def startServer[I, O](connection: ActorRef,
                                exchange: String,
                                requestHandler: I => O,
                                routingKey: Option[String] = None,
                                queueName: Option[String] = None,
                                durable: Boolean = false,
                                autoDelete: Boolean = true,
                                serializer: RpcServerSerializer[I, O]): RpcServerHandle = {

    val exchangeParameters = ExchangeParameters(exchange, ExchangeType.Topic,
      exchangeDurable = durable, exchangeAutoDelete = autoDelete)
    val rKey = routingKey.getOrElse("%s.request".format(exchange))
    val qName = queueName.getOrElse("%s.in".format(rKey))

    newRpcServer(connection, exchangeParameters, rKey, serializer, requestHandler, queueName = Some(qName))
  }
}

