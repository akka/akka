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
import org.multiverse.api.latches.StandardLatch
import java.util.concurrent.TimeUnit

class AMQPRpcProtobufTest extends JUnitSuite with MustMatchers {

  @Test
  def consumerMessage = if (AMQPTest.enabled) AMQPTest.withCleanEndState {

    val connection = AMQP.newConnection()

    RPC.newProtobufRpcServer(connection, "protoservice", requestHandler)

    val protobufClient = RPC.newProtobufRpcClient[AddressProtocol, AddressProtocol](connection, "protoservice")

    val request = AddressProtocol.newBuilder.setHostname("testhost").setPort(4321).build

    protobufClient.call(request) match {
      case Some(response) => assert(response.getHostname == request.getHostname.reverse)
      case None => fail("no response")
    }

    val aSyncLatch = new StandardLatch
    protobufClient.callAsync(request) {
      case Some(response) => {
        assert(response.getHostname == request.getHostname.reverse)
        aSyncLatch.open
      }
      case None => fail("no response")
    }

    aSyncLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)

  }

  def requestHandler(request: AddressProtocol): AddressProtocol = {
    AddressProtocol.newBuilder.setHostname(request.getHostname.reverse).setPort(request.getPort).build
  }
}