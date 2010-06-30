package se.scalablesolutions.akka.camel

import java.net.InetSocketAddress

import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.{AspectInit, ActiveObject}
import se.scalablesolutions.akka.camel.ConsumerMethodRegistered._

class ConsumerMethodRegisteredTest extends JUnitSuite {
  val remoteAddress = new InetSocketAddress("localhost", 8888);
  val remoteAspectInit = AspectInit(classOf[String], null, Some(remoteAddress), 1000)
  val localAspectInit = AspectInit(classOf[String], null, None, 1000)

  val activePojoBase = ActiveObject.newInstance(classOf[PojoBase])
  val activePojoSub = ActiveObject.newInstance(classOf[PojoSub])
  val activePojoIntf = ActiveObject.newInstance(classOf[PojoIntf], new PojoImpl)

  val ascendingMethodName = (r1: ConsumerMethodRegistered, r2: ConsumerMethodRegistered) =>
    r1.method.getName < r2.method.getName

  @Test def shouldSelectPojoBaseMethods234 = {
    val registered = forConsumer(activePojoBase, localAspectInit).sortWith(ascendingMethodName)
    assert(registered.size === 3)
    assert(registered.map(_.method.getName) === List("m2", "m3", "m4"))
  }

  @Test def shouldSelectPojoSubMethods134 = {
    val registered = forConsumer(activePojoSub, localAspectInit).sortWith(ascendingMethodName)
    assert(registered.size === 3)
    assert(registered.map(_.method.getName) === List("m1", "m3", "m4"))
  }

  @Test def shouldSelectPojoIntfMethod2 = {
    val registered = forConsumer(activePojoIntf, localAspectInit)
    assert(registered.size === 1)
    assert(registered(0).method.getName === "m2")
  }

  @Test def shouldIgnoreRemoteProxies = {
    val registered = forConsumer(activePojoBase, remoteAspectInit)
    assert(registered.size === 0)
  }

}
