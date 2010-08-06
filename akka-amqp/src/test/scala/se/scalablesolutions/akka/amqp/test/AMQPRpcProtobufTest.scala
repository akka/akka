package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import se.scalablesolutions.akka.amqp.AMQP
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol.AddressProtocol
import org.junit.Test
import se.scalablesolutions.akka.amqp.rpc.RPC

class AMQPRpcProtobufTest extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = if (AMQPTest.enabled) {

    val connection = AMQP.newConnection()

    RPC.startProtobufServer(connection, "protoservice", requestHandler)

    val protobufClient = RPC.startProtobufClient[AddressProtocol, AddressProtocol](connection, "protoservice")

    val request = AddressProtocol.newBuilder.setHostname("testhost").setPort(4321).build

    protobufClient.callService(request) match {
      case Some(response) => assert(response.getHostname == request.getHostname.reverse)
      case None => fail("no response")
    }
  }

  def requestHandler(request: AddressProtocol): AddressProtocol = {
    AddressProtocol.newBuilder.setHostname(request.getHostname.reverse).setPort(request.getPort).build
  }
}