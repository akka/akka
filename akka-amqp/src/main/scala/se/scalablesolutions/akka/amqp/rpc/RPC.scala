package se.scalablesolutions.akka.amqp.rpc

import se.scalablesolutions.akka.amqp.AMQP._
import com.google.protobuf.Message
import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import Actor._
import se.scalablesolutions.akka.amqp._
import se.scalablesolutions.akka.util.Procedure
import reflect.Manifest

object RPC {

  // Needed for Java API usage
  def newRpcClient[O, I](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcClientSerializer[O, I]): ActorRef = {
    newRpcClient(connection, exchangeName, routingKey, serializer, None)
  }

  // Needed for Java API usage
  def newRpcClient[O, I](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcClientSerializer[O, I],
                         channelParameters: ChannelParameters): ActorRef = {
    newRpcClient(connection, exchangeName, routingKey, serializer, Some(channelParameters))
  }

  def newRpcClient[O, I](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcClientSerializer[O, I],
                         channelParameters: Option[ChannelParameters] = None): ActorRef = {
    val rpcActor: ActorRef = actorOf(new RpcClientActor[O, I](
      ExchangeParameters(exchangeName, exchangeDeclaration = PassiveDeclaration), routingKey, serializer, channelParameters))
    connection.startLink(rpcActor)
    rpcActor ! Start
    rpcActor
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: se.scalablesolutions.akka.util.Function[I,O]): RpcServerHandle = {
    newRpcServer(connection, exchangeName, routingKey, serializer, requestHandler.apply _)
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: se.scalablesolutions.akka.util.Function[I,O],
                         queueName: String): RpcServerHandle = {
    newRpcServer(connection, exchangeName, routingKey, serializer, requestHandler.apply _, Some(queueName))
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: se.scalablesolutions.akka.util.Function[I,O],
                         channelParameters: ChannelParameters): RpcServerHandle = {
    newRpcServer(connection, exchangeName, routingKey, serializer, requestHandler.apply _, None, Some(channelParameters))
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: se.scalablesolutions.akka.util.Function[I,O],
                         queueName: String,
                         channelParameters: ChannelParameters): RpcServerHandle = {
    newRpcServer(connection, exchangeName, routingKey, serializer, requestHandler.apply _, Some(queueName), Some(channelParameters))
  }

  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: I => O,
                         queueName: Option[String] = None,
                         channelParameters: Option[ChannelParameters] = None): RpcServerHandle = {

    val producer = newProducer(connection, ProducerParameters(channelParameters = channelParameters))
    val rpcServer = actorOf(new RpcServerActor[I, O](producer, serializer, requestHandler))
    val consumer = newConsumer(connection, ConsumerParameters(routingKey, rpcServer,
      exchangeParameters = Some(ExchangeParameters(exchangeName)), channelParameters = channelParameters,
      selfAcknowledging = false, queueName = queueName))
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

    // Needed for Java API usage
    def call(request: O): Option[I] = {
      call(request, 5000)
    }

    def call(request: O, timeout: Long = 5000): Option[I] = {
      (client.!!(request, timeout)).as[I]
    }

    // Needed for Java API usage
    def callAsync(request: O, responseHandler: Procedure[I]): Unit = {
      callAsync(request, 5000, responseHandler)
    }

    // Needed for Java API usage
    def callAsync(request: O, timeout: Long, responseHandler: Procedure[I]): Unit = {
      callAsync(request, timeout){
        case Some(response) => responseHandler.apply(response)
      }
    }

    def callAsync(request: O, timeout: Long = 5000)(responseHandler: PartialFunction[Option[I],Unit]) = {
      spawn {
        val result = call(request, timeout)
        responseHandler.apply(result)
      }
    }
    def stop = client.stop
  }


  // Needed for Java API usage
  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: se.scalablesolutions.akka.util.Function[I,O],
          resultClazz: Class[I]): RpcServerHandle = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcServer(connection, exchangeName, requestHandler.apply _)
  }

  // Needed for Java API usage
  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: se.scalablesolutions.akka.util.Function[I,O],
          routingKey: String,
          resultClazz: Class[I]): RpcServerHandle = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey))
  }

  // Needed for Java API usage
  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: se.scalablesolutions.akka.util.Function[I,O],
          routingKey: String,
          queueName: String,
          resultClazz: Class[I]): RpcServerHandle = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey), Some(queueName))
  }

  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: I => O,
          routingKey: Option[String] = None,
          queueName: Option[String] = None)(implicit manifest: Manifest[I]): RpcServerHandle = {

    val serializer = new RpcServerSerializer[I, O](
      new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      }, new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      })

    startServer(connection, exchangeName, requestHandler, routingKey, queueName, serializer)
  }

  // Needed for Java API usage
  def newProtobufRpcClient[O <: Message, I <: Message](
          connection: ActorRef,
          exchangeName: String,
          resultClazz: Class[I]): RpcClient[O, I] = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcClient(connection, exchangeName, None)
  }

  // Needed for Java API usage
  def newProtobufRpcClient[O <: Message, I <: Message](
          connection: ActorRef,
          exchangeName: String,
          routingKey: String,
          resultClazz: Class[I]): RpcClient[O, I] = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcClient(connection, exchangeName, Some(routingKey))
  }

  def newProtobufRpcClient[O <: Message, I <: Message](
          connection: ActorRef,
          exchangeName: String,
          routingKey: Option[String] = None)(implicit manifest: Manifest[I]): RpcClient[O, I] = {


    val serializer = new RpcClientSerializer[O, I](
      new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      }, new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      })

    startClient(connection, exchangeName, routingKey, serializer)
  }

  // Needed for Java API usage
  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: se.scalablesolutions.akka.util.Function[String,String]): RpcServerHandle = {
    newStringRpcServer(connection, exchangeName, requestHandler.apply _)
  }

  // Needed for Java API usage
  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: se.scalablesolutions.akka.util.Function[String,String],
                        routingKey: String): RpcServerHandle = {
    newStringRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey))
  }

  // Needed for Java API usage
  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: se.scalablesolutions.akka.util.Function[String,String],
                        routingKey: String,
                        queueName: String): RpcServerHandle = {
    newStringRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey), Some(queueName))
  }

  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: String => String,
                        routingKey: Option[String] = None,
                        queueName: Option[String] = None): RpcServerHandle = {

    val serializer = new RpcServerSerializer[String, String](
      new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]): String = {
          new String(bytes)
        }
      }, new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      })

    startServer(connection, exchangeName, requestHandler, routingKey, queueName, serializer)
  }

  // Needed for Java API usage
  def newStringRpcClient(connection: ActorRef,
                        exchange: String): RpcClient[String, String] = {
    newStringRpcClient(connection, exchange, None)
  }  

  // Needed for Java API usage
  def newStringRpcClient(connection: ActorRef,
                        exchange: String,
                        routingKey: String): RpcClient[String, String] = {
    newStringRpcClient(connection, exchange, Some(routingKey))
  }

  def newStringRpcClient(connection: ActorRef,
                        exchange: String,
                        routingKey: Option[String] = None): RpcClient[String, String] = {


    val serializer = new RpcClientSerializer[String, String](
      new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      }, new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]): String = {
          new String(bytes)
        }
      })

    startClient(connection, exchange, routingKey, serializer)
  }

  private def startClient[O, I](connection: ActorRef,
                                exchangeName: String,
                                routingKey: Option[String] = None,
                                serializer: RpcClientSerializer[O, I]): RpcClient[O, I] = {

    val rKey = routingKey.getOrElse("%s.request".format(exchangeName))

    val client = newRpcClient(connection, exchangeName, rKey, serializer)
    new RpcClient(client)
  }

  private def startServer[I, O](connection: ActorRef,
                                exchangeName: String,
                                requestHandler: I => O,
                                routingKey: Option[String] = None,
                                queueName: Option[String] = None,
                                serializer: RpcServerSerializer[I, O]): RpcServerHandle = {

    val rKey = routingKey.getOrElse("%s.request".format(exchangeName))
    val qName = queueName.getOrElse("%s.in".format(rKey))

    newRpcServer(connection, exchangeName, rKey, serializer, requestHandler, Some(qName))
  }
}

