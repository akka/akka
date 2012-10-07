package akka.remote.testconductor

import akka.actor.{ ExtensionKey, Extension, ExtendedActorSystem, ActorContext, ActorRef, Address, ActorSystemImpl, Props }
import akka.remote.RemoteActorRefProvider
import akka.util.Timeout
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.util.Duration

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
    val config = system.settings.config

    val ConnectTimeout = Duration(config.getMilliseconds("akka.testconductor.connect-timeout"), MILLISECONDS)
    val ClientReconnects = config.getInt("akka.testconductor.client-reconnects")
    val ReconnectBackoff = Duration(config.getMilliseconds("akka.testconductor.reconnect-backoff"), MILLISECONDS)

    implicit val BarrierTimeout = Timeout(Duration(config.getMilliseconds("akka.testconductor.barrier-timeout"), MILLISECONDS))
    implicit val QueryTimeout = Timeout(Duration(config.getMilliseconds("akka.testconductor.query-timeout"), MILLISECONDS))
    val PacketSplitThreshold = Duration(config.getMilliseconds("akka.testconductor.packet-split-threshold"), MILLISECONDS)
  }

  /**
   * Remote transport used by the actor ref provider.
   */
  val transport = system.provider.asInstanceOf[RemoteActorRefProvider].transport

  /**
   * Transport address of this Netty-like remote transport.
   */
  val address = transport.address

  /**
   * INTERNAL API.
   *
   * [[akka.remote.testconductor.NetworkFailureInjector]]s register themselves here so that
   * failures can be injected.
   */
  private[akka] val failureInjector = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props[FailureInjector], "FailureInjector")

}
