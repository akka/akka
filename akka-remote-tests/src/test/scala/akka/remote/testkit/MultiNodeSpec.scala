/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testkit

import akka.testkit.AkkaSpec
import akka.actor.ActorSystem
import akka.remote.testconductor.TestConductor
import java.net.InetAddress
import java.net.InetSocketAddress
import akka.remote.testconductor.TestConductorExt
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.dispatch.Await.Awaitable
import akka.dispatch.Await
import akka.util.Duration
import akka.actor.ActorPath
import akka.actor.RootActorPath

object MultiNodeSpec {

  /**
   * Names (or IP addresses; must be resolvable using InetAddress.getByName)
   * of all nodes taking part in this test, including symbolic name and host
   * definition:
   *
   * {{{
   * -D"multinode.hosts=host1@workerA.example.com,host2@workerB.example.com"
   * }}}
   */
  val nodeNames: Seq[String] = Vector.empty ++ (
    Option(System.getProperty("multinode.hosts")) getOrElse
    (throw new IllegalStateException("need system property multinode.hosts to be set")) split ",")

  require(nodeNames != List(""), "multinode.hosts must not be empty")

  /**
   * Index of this node in the nodeNames / nodeAddresses lists. The TestConductor
   * is started in “controller” mode on selfIndex 0, i.e. there you can inject
   * failures and shutdown other nodes etc.
   */
  val selfIndex = Option(Integer.getInteger("multinode.index")) getOrElse
    (throw new IllegalStateException("need system property multinode.index to be set"))

  require(selfIndex >= 0 && selfIndex < nodeNames.size, "selfIndex out of bounds: " + selfIndex)

  val nodeConfig = AkkaSpec.mapToConfig(Map(
    "akka.actor.provider" -> "akka.remote.RemoteActorRefProvider",
    "akka.remote.transport" -> "akka.remote.testconductor.TestConductorTransport",
    "akka.remote.netty.hostname" -> nodeNames(selfIndex),
    "akka.remote.netty.port" -> 0))

}

abstract class MultiNodeSpec(_system: ActorSystem) extends AkkaSpec(_system) {

  import MultiNodeSpec._

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName,
    MultiNodeSpec.nodeConfig.withFallback(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(AkkaSpec.testConf)

  /*
   * Test Class Interface
   */

  /**
   * TO BE DEFINED BY USER: Defines the number of participants required for starting the test. This
   * might not be equals to the number of nodes available to the test.
   *
   * Must be a `def`:
   * {{{
   * def initialParticipants = 5
   * }}}
   */
  def initialParticipants: Int
  require(initialParticipants > 0, "initialParticipants must be a 'def' or early initializer, and it must be greater zero")
  require(initialParticipants <= nodeNames.size, "not enough nodes to run this test")

  /**
   * Access to the barriers, failure injection, etc. The extension will have
   * been started either in Conductor or Player mode when the constructor of
   * MultiNodeSpec finishes, i.e. do not call the start*() methods yourself!
   */
  val testConductor: TestConductorExt = TestConductor(system)

  /**
   * TO BE DEFINED BY USER: The test class must define a set of role names to
   * be used throughout the run, e.g. in naming nodes in failure injections.
   * These will be mapped to the available nodes such that the first name will
   * be the Controller, i.e. on this one you can do failure injection.
   *
   * Should be a lazy val due to initialization order:
   * {{{
   * lazy val roles = Seq("master", "slave")
   * }}}
   */
  def roles: Seq[String]

  require(roles.size >= initialParticipants, "not enough roles for initialParticipants")
  require(roles.size <= nodeNames.size, "not enough nodes for number of roles")
  require(roles.distinct.size == roles.size, "role names must be distinct")

  val mySelf = {
    if (selfIndex >= roles.size) System.exit(0)
    roles(selfIndex)
  }

  /**
   * Execute the given block of code only on the given nodes (names according
   * to the `roleMap`).
   */
  def runOn(nodes: String*)(thunk: ⇒ Unit): Unit = {
    if (nodes exists (_ == mySelf)) {
      thunk
    }
  }

  def ifNode[T](nodes: String*)(yes: ⇒ T)(no: ⇒ T): T = {
    if (nodes exists (_ == mySelf)) yes else no
  }

  /**
   * Query the controller for the transport address of the given node (by role name) and
   * return that as an ActorPath for easy composition:
   *
   * {{{
   * val serviceA = system.actorFor(node("master") / "user" / "serviceA")
   * }}}
   */
  def node(name: String): ActorPath = RootActorPath(testConductor.getAddressFor(name).await)

  /**
   * Enrich `.await()` onto all Awaitables, using BarrierTimeout.
   */
  implicit def awaitHelper[T](w: Awaitable[T]) = new AwaitHelper(w)
  class AwaitHelper[T](w: Awaitable[T]) {
    def await: T = Await.result(w, testConductor.Settings.BarrierTimeout.duration)
  }

  /*
   * Implementation (i.e. wait for start etc.)
   */

  private val controllerAddr = new InetSocketAddress(nodeNames(0), 4711)
  if (selfIndex == 0) {
    testConductor.startController(initialParticipants, roles(0), controllerAddr).await
  } else {
    testConductor.startClient(roles(selfIndex), controllerAddr).await
  }

}