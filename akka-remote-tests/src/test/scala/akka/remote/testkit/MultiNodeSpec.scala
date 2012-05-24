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
import akka.remote.testconductor.RoleName

/**
 * Configure the role names and participants of the test, including configuration settings.
 */
abstract class MultiNodeConfig {

  private var _commonConf: Option[Config] = None
  private var _nodeConf = Map[RoleName, Config]()
  private var _roles = Seq[RoleName]()

  /**
   * Register a common base config for all test participants, if so desired.
   */
  def commonConfig(config: Config): Unit = _commonConf = Some(config)

  /**
   * Register a config override for a specific participant.
   */
  def nodeConfig(role: RoleName, config: Config): Unit = _nodeConf += role -> config

  /**
   * Include for verbose debug logging
   * @param on when `true` debug Config is returned, otherwise empty Config
   */
  def debugConfig(on: Boolean): Config =
    if (on)
      ConfigFactory.parseString("""
        akka.loglevel = DEBUG
        akka.remote {
          log-received-messages = on
          log-sent-messages = on
        }
        akka.actor.debug {
          receive = on
          fsm = on
        }
        """)
    else ConfigFactory.empty

  /**
   * Construct a RoleName and return it, to be used as an identifier in the
   * test. Registration of a role name creates a role which then needs to be
   * filled.
   */
  def role(name: String): RoleName = {
    if (_roles exists (_.name == name)) throw new IllegalArgumentException("non-unique role name " + name)
    val r = RoleName(name)
    _roles :+= r
    r
  }

  private[testkit] lazy val mySelf: RoleName = {
    require(_roles.size > MultiNodeSpec.selfIndex, "not enough roles declared for this test")
    _roles(MultiNodeSpec.selfIndex)
  }

  private[testkit] def config: Config = {
    val configs = (_nodeConf get mySelf).toList ::: _commonConf.toList ::: MultiNodeSpec.nodeConfig :: AkkaSpec.testConf :: Nil
    configs reduce (_ withFallback _)
  }

}

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

abstract class MultiNodeSpec(val mySelf: RoleName, _system: ActorSystem) extends AkkaSpec(_system) {

  import MultiNodeSpec._

  def this(config: MultiNodeConfig) = this(config.mySelf, ActorSystem(AkkaSpec.getCallerName(classOf[MultiNodeSpec]), config.config))

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
   * Execute the given block of code only on the given nodes (names according
   * to the `roleMap`).
   */
  def runOn(nodes: RoleName*)(thunk: ⇒ Unit): Unit = {
    if (nodes exists (_ == mySelf)) {
      thunk
    }
  }

  def ifNode[T](nodes: RoleName*)(yes: ⇒ T)(no: ⇒ T): T = {
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
  def node(role: RoleName): ActorPath = RootActorPath(testConductor.getAddressFor(role).await)

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
    testConductor.startController(initialParticipants, mySelf, controllerAddr).await
  } else {
    testConductor.startClient(mySelf, controllerAddr).await
  }

}