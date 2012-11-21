package akka.remote.testconductor

import akka.actor.{ ExtensionKey, Extension, ExtendedActorSystem, ActorContext, ActorRef, Address, ActorSystemImpl, Props }
import akka.remote.RemoteActorRefProvider
import akka.util.Timeout
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.Duration
import com.typesafe.config.Config
import akka.dispatch.ThreadPoolConfig

/**
 * Access to the [[akka.remote.testconductor.TestConductorExt]] extension:
 *
 * {{{
 * val tc = TestConductor(system)
 * tc.startController(numPlayers)
 * // OR
 * tc.startClient(conductorPort)
 * }}}
 */
object TestConductor extends ExtensionKey[TestConductorExt] {

  def apply()(implicit ctx: ActorContext): TestConductorExt = apply(ctx.system)

}

/**
 * This binds together the [[akka.remote.testconductor.Conductor]] and
 * [[akka.remote.testconductor.Player]] roles inside an Akka
 * [[akka.actor.Extension]]. Please follow the aforementioned links for
 * more information.
 *
 * ====Note====
 * This extension requires the `akka.actor.provider`
 * to be a [[akka.remote.RemoteActorRefProvider]].
 *
 * To use ``blackhole``, ``passThrough``, and ``throttle`` you must activate the
 * `TestConductorTranport` by specifying `testTransport(on = true)` in your
 * MultiNodeConfig.
 *
 */
class TestConductorExt(val system: ExtendedActorSystem) extends Extension with Conductor with Player {

  object Settings {
    val config = system.settings.config.getConfig("akka.testconductor")

    val ConnectTimeout = Duration(config.getMilliseconds("connect-timeout"), MILLISECONDS)
    val ClientReconnects = config.getInt("client-reconnects")
    val ReconnectBackoff = Duration(config.getMilliseconds("reconnect-backoff"), MILLISECONDS)

    implicit val BarrierTimeout = Timeout(Duration(config.getMilliseconds("barrier-timeout"), MILLISECONDS))
    implicit val QueryTimeout = Timeout(Duration(config.getMilliseconds("query-timeout"), MILLISECONDS))
    val PacketSplitThreshold = Duration(config.getMilliseconds("packet-split-threshold"), MILLISECONDS)

    private def computeWPS(config: Config): Int =
      ThreadPoolConfig.scaledPoolSize(
        config.getInt("pool-size-min"),
        config.getDouble("pool-size-factor"),
        config.getInt("pool-size-max"))

    val ServerSocketWorkerPoolSize = computeWPS(config.getConfig("netty.server-socket-worker-pool"))

    val ClientSocketWorkerPoolSize = computeWPS(config.getConfig("netty.client-socket-worker-pool"))
  }

  /**
   * Remote transport used by the actor ref provider.
   */
  val transport = system.provider.asInstanceOf[RemoteActorRefProvider].transport

  /**
   * Transport address of this Netty-like remote transport.
   */
  val address = transport.addresses.head //FIXME: Workaround for old-remoting -- must be removed later

  /**
   * INTERNAL API.
   *
   * [[akka.remote.testconductor.NetworkFailureInjector]]s register themselves here so that
   * failures can be injected.
   */
  private[akka] val failureInjector = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props[FailureInjector], "FailureInjector")

}
