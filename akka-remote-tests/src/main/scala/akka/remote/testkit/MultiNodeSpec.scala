/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testkit

import language.implicitConversions
import language.postfixOps

import java.net.InetSocketAddress
import com.typesafe.config.{ ConfigObject, ConfigFactory, Config }
import akka.actor._
import akka.util.Timeout
import akka.remote.testconductor.{ TestConductorExt, TestConductor, RoleName }
import akka.remote.RemoteActorRefProvider
import akka.testkit._
import scala.concurrent.{ Await, Awaitable }
import scala.util.control.NonFatal
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import java.util.concurrent.TimeoutException
import akka.remote.testconductor.RoleName
import akka.remote.testconductor.TestConductorTransport
import akka.actor.RootActorPath
import akka.event.{ Logging, LoggingAdapter }

/**
 * Configure the role names and participants of the test, including configuration settings.
 */
abstract class MultiNodeConfig {

  private var _commonConf: Option[Config] = None
  private var _nodeConf = Map[RoleName, Config]()
  private var _roles = Vector[RoleName]()
  private var _deployments = Map[RoleName, Seq[String]]()
  private var _allDeploy = Vector[String]()
  private var _testTransport = false

  /**
   * Register a common base config for all test participants, if so desired.
   */
  def commonConfig(config: Config): Unit = _commonConf = Some(config)

  /**
   * Register a config override for a specific participant.
   */
  def nodeConfig(role: RoleName*)(config: Config*): Unit =
    for (
      c ← Option(config.reduceLeft(_ withFallback _));
      r ← role
    ) _nodeConf += r -> c

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
        akka.remote.log-remote-lifecycle-events = on
        """)
    else
      ConfigFactory.empty

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

  /**
   * To be able to use `blackhole`, `passThrough`, and `throttle` you must
   * activate the TestConductorTranport by specifying
   * `testTransport(on = true)` in your MultiNodeConfig.
   */
  def testTransport(on: Boolean): Unit = _testTransport = on

  private[testkit] lazy val myself: RoleName = {
    require(_roles.size > MultiNodeSpec.selfIndex, "not enough roles declared for this test")
    _roles(MultiNodeSpec.selfIndex)
  }

  private[testkit] def config: Config = {
    val transportConfig =
      if (_testTransport) ConfigFactory.parseString("akka.remote.transport=" + classOf[TestConductorTransport].getName)
      else ConfigFactory.empty

    val configs = (_nodeConf get myself).toList ::: _commonConf.toList ::: transportConfig :: MultiNodeSpec.nodeConfig :: MultiNodeSpec.baseConfig :: Nil
    configs reduce (_ withFallback _)
  }

  private[testkit] def deployments(node: RoleName): Seq[String] = (_deployments get node getOrElse Nil) ++ _allDeploy

  private[testkit] def roles: Seq[RoleName] = _roles

}

object MultiNodeSpec {

  /**
   * Number of nodes node taking part in this test.
   *
   * {{{
   * -Dmultinode.max-nodes=4
   * }}}
   */
  val maxNodes: Int = Option(Integer.getInteger("multinode.max-nodes")) getOrElse
    (throw new IllegalStateException("need system property multinode.max-nodes to be set"))

  require(maxNodes > 0, "multinode.max-nodes must be greater than 0")

  /**
   * Name (or IP address; must be resolvable using InetAddress.getByName)
   * of the host this node is running on.
   *
   * {{{
   * -Dmultinode.host=host.example.com
   * }}}
   */
  val selfName: String = Option(System.getProperty("multinode.host")) getOrElse
    (throw new IllegalStateException("need system property multinode.host to be set"))

  require(selfName != "", "multinode.host must not be empty")

  /**
   * Port number of this node. Defaults to 0 which means a random port.
   *
   * {{{
   * -Dmultinode.port=0
   * }}}
   */
  val selfPort: Int = Integer.getInteger("multinode.port", 0)

  require(selfPort >= 0 && selfPort < 65535, "multinode.port is out of bounds: " + selfPort)

  /**
   * Name (or IP address; must be resolvable using InetAddress.getByName)
   * of the host that the server node is running on.
   *
   * {{{
   * -Dmultinode.server-host=server.example.com
   * }}}
   */
  val serverName: String = Option(System.getProperty("multinode.server-host")) getOrElse
    (throw new IllegalStateException("need system property multinode.server-host to be set"))

  require(serverName != "", "multinode.server-host must not be empty")

  /**
   * Port number of the node that's running the server system. Defaults to 4711.
   *
   * {{{
   * -Dmultinode.server-port=4711
   * }}}
   */
  val serverPort: Int = Integer.getInteger("multinode.server-port", 4711)

  require(serverPort > 0 && serverPort < 65535, "multinode.server-port is out of bounds: " + serverPort)

  /**
   * Index of this node in the roles sequence. The TestConductor
   * is started in “controller” mode on selfIndex 0, i.e. there you can inject
   * failures and shutdown other nodes etc.
   *
   * {{{
   * -Dmultinode.index=0
   * }}}
   */
  val selfIndex = Option(Integer.getInteger("multinode.index")) getOrElse
    (throw new IllegalStateException("need system property multinode.index to be set"))

  require(selfIndex >= 0 && selfIndex < maxNodes, "multinode.index is out of bounds: " + selfIndex)

  private[testkit] val nodeConfig = mapToConfig(Map(
    "akka.actor.provider" -> "akka.remote.RemoteActorRefProvider",
    "akka.remote.netty.hostname" -> selfName,
    "akka.remote.netty.port" -> selfPort))

  private[testkit] val baseConfig: Config = ConfigFactory.parseString("""
      akka {
        event-handlers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
        }
      }
      """)

  private def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  private def getCallerName(clazz: Class[_]): String = {
    val s = Thread.currentThread.getStackTrace map (_.getClassName) drop 1 dropWhile (_ matches ".*MultiNodeSpec.?$")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }
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
  extends TestKit(_system) with MultiNodeSpecCallbacks {

  import MultiNodeSpec._

  def this(config: MultiNodeConfig) =
    this(config.myself, ActorSystem(MultiNodeSpec.getCallerName(classOf[MultiNodeSpec]), ConfigFactory.load(config.config)),
      config.roles, config.deployments)

  val log: LoggingAdapter = Logging(system, this.getClass)

  final override def multiNodeSpecBeforeAll {
    atStartup()
  }

  final override def multiNodeSpecAfterAll {
    // wait for all nodes to remove themselves before we shut the conductor down
    if (selfIndex == 0) {
      testConductor.removeNode(myself)
      within(testConductor.Settings.BarrierTimeout.duration) {
        awaitCond {
          testConductor.getNodes.await.filterNot(_ == myself).isEmpty
        }
      }
    }
    system.shutdown()
    val shutdownTimeout = 5.seconds.dilated
    try system.awaitTermination(shutdownTimeout) catch {
      case _: TimeoutException ⇒
        val msg = "Failed to stop [%s] within [%s] \n%s".format(system.name, shutdownTimeout,
          system.asInstanceOf[ActorSystemImpl].printTree)
        if (verifySystemShutdown) throw new RuntimeException(msg)
        else system.log.warning(msg)
    }
    atTermination()
  }

  /**
   * Override this and return `true` to assert that the
   * shutdown of the `ActorSystem` was done properly.
   */
  def verifySystemShutdown: Boolean = false

  /*
  * Test Class Interface
  */

  /**
   * Override this method to do something when the whole test is starting up.
   */
  protected def atStartup(): Unit = {}

  /**
   * Override this method to do something when the whole test is terminating.
   */
  protected def atTermination(): Unit = {}

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
  require(initialParticipants <= maxNodes, "not enough nodes to run this test")

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
  def runOn(nodes: RoleName*)(thunk: ⇒ Unit): Unit =
    if (nodes exists (_ == myself)) {
      thunk
    }

  /**
   * Execute the given block of code only on the given nodes (names according
   * to the `roleMap`).
   */
  def runOn[I](nodes: (RoleName, I)*)(thunk: I ⇒ Unit): Unit =
    nodes foreach {
      case (`myself`, t) ⇒ thunk(t)
      case _             ⇒
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
   * verify that the running node matches one of the given nodes
   */
  def isNode(nodes: RoleName*): Boolean = nodes exists (_ == myself)

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

  private val controllerAddr = new InetSocketAddress(serverName, serverPort)
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

  // useful to see which jvm is running which role, used by LogRoleReplace utility
  log.info("Role [{}] started with address [{}]", myself.name,
    system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.address)

}

/**
 * Use this to hook MultiNodeSpec into your test framework lifecycle, either by having your test extend MultiNodeSpec
 * and call these methods or by creating a trait that calls them and then mixing that trait with your test together
 * with MultiNodeSpec.
 *
 * Example trait for MultiNodeSpec with ScalaTest
 *
 * {{{
 * trait STMultiNodeSpec extends MultiNodeSpecCallbacks with WordSpec with MustMatchers with BeforeAndAfterAll {
 *   override def beforeAll() = multiNodeSpecBeforeAll()
 *   override def afterAll() = multiNodeSpecAfterAll()
 * }
 * }}}
 */
trait MultiNodeSpecCallbacks {
  /**
   * Call this before the start of the test run. NOT before every test case.
   */
  def multiNodeSpecBeforeAll(): Unit

  /**
   * Call this after the all test cases have run. NOT after every test case.
   */
  def multiNodeSpecAfterAll(): Unit
}
