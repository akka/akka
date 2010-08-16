package se.scalablesolutions.akka.camel

import java.net.InetSocketAddress

import org.junit.{AfterClass, Test}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.{AspectInit, TypedActor}
import se.scalablesolutions.akka.camel.ConsumerMethodRegistered._

class ConsumerMethodRegisteredTest extends JUnitSuite {
  import ConsumerMethodRegisteredTest._

  val remoteAddress = new InetSocketAddress("localhost", 8888);
  val remoteAspectInit = AspectInit(classOf[SampleTypedConsumer], new SampleTypedConsumerImpl, null, Some(remoteAddress), 1000)
  val localAspectInit = AspectInit(classOf[SampleTypedConsumer], new SampleTypedConsumerImpl, null, None, 1000)
  val ascendingMethodName = (r1: ConsumerMethodRegistered, r2: ConsumerMethodRegistered) =>
    r1.method.getName < r2.method.getName

  @Test def shouldSelectTypedActorMethods234 = {
    val registered = forConsumer(typedConsumer, localAspectInit).sortWith(ascendingMethodName)
    assert(registered.size === 3)
    assert(registered.map(_.method.getName) === List("m2", "m3", "m4"))
  }

  @Test def shouldIgnoreRemoteProxies = {
    val registered = forConsumer(typedConsumer, remoteAspectInit)
    assert(registered.size === 0)
  }
}

object ConsumerMethodRegisteredTest {
  val typedConsumer = TypedActor.newInstance(classOf[SampleTypedConsumer], classOf[SampleTypedConsumerImpl])

  @AfterClass 
  def afterClass = TypedActor.stop(typedConsumer)
}
