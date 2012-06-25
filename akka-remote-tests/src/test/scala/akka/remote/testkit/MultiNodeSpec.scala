/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testkit

import language.implicitConversions

import java.net.InetSocketAddress

import com.typesafe.config.{ ConfigObject, ConfigFactory, Config }

import akka.actor.{ RootActorPath, Deploy, ActorPath, ActorSystem, ExtendedActorSystem }
import akka.dispatch.Await
import akka.dispatch.Await.Awaitable
import akka.remote.testconductor.{ TestConductorExt, TestConductor, RoleName }
import akka.testkit.AkkaSpec
import akka.util.{ Timeout, NonFatal, Duration }

/**
 * Configure the role names and participants of the test, including configuration settings.
 */
abstract class MultiNodeConfig {

  private var _commonConf: Option[Config] = None
  private var _nodeConf = Map[RoleName, Config]()
  private var _roles = Vector[RoleName]()
  private var _deployments = Map[RoleName, Seq[String]]()
  private var _allDeploy = Vector[String]()

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
   * @param on when `true` debug Config is returned, otherwise config with info logging
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
    else
      ConfigFactory.parseString("akka.loglevel = INFO")

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

  def deployOn(role: RoleName, deployment: String): Unit =
    _deployments += role -> ((_deployments get role getOrElse Vector()) :+ deployment)

  def deployOnAll(deployment: String): Unit = _allDeploy :+= deployment

  private[testkit] lazy val myself: RoleName = {
    require(_roles.size > MultiNodeSpec.selfIndex, "not enough roles declared for this test")
    _roles(MultiNodeSpec.selfIndex)
  }

  private[testkit] def config: Config = {
    val configs = (_nodeConf get myself).toList ::: _commonConf.toList ::: MultiNodeSpec.nodeConfig :: AkkaSpec.testConf :: Nil
    configs reduce (_ withFallback _)
  }

  private[testkit] def deployments(node: RoleName): Seq[String] = (_deployments get node getOrElse Nil) ++ _allDeploy

  private[testkit] def roles: Seq[RoleName] = _roles

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

/**
 * Note: To be able to run tests with everything ignored or excluded by tags
 * you must not use `testconductor`, or helper methods that use `testconductor`,
 * from the constructor of your test class. Otherwise the controller node might
 * be shutdown before other nodes have completed and you will see errors like:
 * `AskTimeoutException: sending to terminated ref breaks promises`. Using lazy
 * val is fine.
 */
abstract class MultiNodeSpec(val myself: RoleName, _system: ActorSystem, _roles: Seq[RoleName], deployments: RoleName ⇒ Seq[String])
  extends AkkaSpec(_system) {

  import MultiNodeSpec._

  def this(config: MultiNodeConfig) =
    this(config.myself, ActorSystem(AkkaSpec.getCallerName(classOf[MultiNodeSpec]), config.config), config.roles, config.deployments)

  /*
   * Test Class Interface
   */

  /**
   * All registered roles
   */
  def roles: Seq[RoleName] = _roles

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
    if (nodes exists (_ == myself)) {
      thunk
    }
  }

  def ifNode[T](nodes: RoleName*)(yes: ⇒ T)(no: ⇒ T): T = {
    if (nodes exists (_ == myself)) yes else no
  }

  /**
   * Enter the named barriers in the order given. Use the remaining duration from
   * the innermost enclosing `within` block or the default `BarrierTimeout`
   */
  def enterBarrier(name: String*) {
    testConductor.enter(Timeout.durationToTimeout(remainingOr(testConductor.Settings.BarrierTimeout.duration)), name)
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
   * Enrich `.await()` onto all Awaitables, using remaining duration from the innermost
   * enclosing `within` block or QueryTimeout.
   */
  implicit def awaitHelper[T](w: Awaitable[T]) = new AwaitHelper(w)
  class AwaitHelper[T](w: Awaitable[T]) {
    def await: T = Await.result(w, remainingOr(testConductor.Settings.QueryTimeout.duration))
  }

  /*
   * Implementation (i.e. wait for start etc.)
   */

  private val controllerAddr = new InetSocketAddress(nodeNames(0), 4711)
  if (selfIndex == 0) {
    Await.result(testConductor.startController(initialParticipants, myself, controllerAddr),
      testConductor.Settings.BarrierTimeout.duration)
  } else {
    Await.result(testConductor.startClient(myself, controllerAddr),
      testConductor.Settings.BarrierTimeout.duration)
  }

  // now add deployments, if so desired

  private case class Replacement(tag: String, role: RoleName) {
    lazy val addr = node(role).address.toString
  }
  private val replacements = roles map (r ⇒ Replacement("@" + r.name + "@", r))
  private val deployer = system.asInstanceOf[ExtendedActorSystem].provider.deployer
  deployments(myself) foreach { str ⇒
    val deployString = (str /: replacements) {
      case (base, r @ Replacement(tag, _)) ⇒
        base.indexOf(tag) match {
          case -1 ⇒ base
          case start ⇒
            val replaceWith = try
              r.addr
            catch {
              case NonFatal(e) ⇒
                // might happen if all test cases are ignored (excluded) and
                // controller node is finished/exited before r.addr is run
                // on the other nodes
                val unresolved = "akka://unresolved-replacement-" + r.role.name
                log.warning(unresolved + " due to: " + e.getMessage)
                unresolved
            }
            base.replace(tag, replaceWith)
        }
    }
    import scala.collection.JavaConverters._
    ConfigFactory.parseString(deployString).root.asScala foreach {
      case (key, value: ConfigObject) ⇒
        deployer.parseConfig(key, value.toConfig) foreach deployer.deploy
      case (key, x) ⇒
        throw new IllegalArgumentException("key " + key + " must map to deployment section, not simple value " + x)
    }
  }

  // useful to see which jvm is running which role
  log.info("Role [{}] started", myself.name)

}
