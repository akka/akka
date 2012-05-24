package akka.remote.testconductor

import akka.actor.ExtensionKey
import akka.actor.Extension
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteActorRefProvider
import akka.actor.ActorContext
import akka.util.{ Duration, Timeout }
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorRef
import java.util.concurrent.ConcurrentHashMap
import akka.actor.Address
import akka.actor.ActorSystemImpl
import akka.actor.Props

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

  val transport = system.provider.asInstanceOf[RemoteActorRefProvider].transport
  val address = transport.address

  val failureInjector = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props[FailureInjector], "FailureInjector")

}