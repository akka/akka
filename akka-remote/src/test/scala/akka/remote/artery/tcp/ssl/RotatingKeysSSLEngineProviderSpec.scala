/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorIdentity
import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.actor.RootActorPath
import akka.actor.setup.ActorSystemSetup
import akka.remote.artery.ArteryMultiNodeSpec
import akka.remote.artery.tcp.SSLEngineProvider
import akka.remote.artery.tcp.SSLEngineProviderSetup
import akka.remote.artery.tcp.TlsTcpSpec
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import akka.testkit.TestProbe
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

// This is an integration tests specifically to test key rotation. Basic happy-path
// integration of RotatingKeysSSLEngineSpec as an SSLEngineProvider for Akka Remote
// is tested in `TlsTcpWithRotatingKeysSSLEngineSpec`
object RotatingKeysSSLEngineProviderSpec {
  val cacheTtlInSeconds = 1
  val configStr: String = {
    s"""
      akka.remote.artery {
        ## the large-messages channel in artery is not used for this tests 
        ## but we're enabling it to test it also creates its own SSLEngine 
        large-message-destinations = [ "/user/large" ]
      }
      akka.remote.artery.ssl {
        ssl-engine-provider = akka.remote.artery.tcp.ssl.RotatingKeysSSLEngineProvider
        rotating-keys-engine {
          key-file = ${getClass.getClassLoader.getResource("ssl/node.example.com.pem").getPath}
          cert-file = ${getClass.getClassLoader.getResource("ssl/node.example.com.crt").getPath}
          ca-cert-file = ${getClass.getClassLoader.getResource("ssl/exampleca.crt").getPath}
          ssl-context-cache-ttl = ${cacheTtlInSeconds}s
        }
      }
    """
  }
  val config: Config = ConfigFactory.parseString(configStr)

}

// In this test each system reads keys/certs from a different temporal folder to control
// which system gets keys rotated.
class RotatingKeysSSLEngineProviderSpec
    extends ArteryMultiNodeSpec(RotatingKeysSSLEngineProviderSpec.config.withFallback(TlsTcpSpec.config))
    with ImplicitSender {
  "Artery with TLS/TCP with RotatingKeysSSLEngine" must {
    "rebuild the SSLContext" in {
      if (!arteryTcpTlsEnabled())
        pending

      // an initial connection between sysA (from the testkit) and sysB
      // to get sysB up and running
      val (remoteSysB, pathEchoB) = buildRemoteWithEchoActor("B")
      contact(system, pathEchoB)
      assertEnginesCreated(remoteSysB)
      val before = remoteSysB.sslContextRef.get()

      awaitCacheExpiration() // temporal break!

      // Send message to system C from system B.
      // Not using system A because we can't get a reference to the SSLContext in system A
      val (remoteSysC, pathEchoC) = buildRemoteWithEchoActor("C")
      contact(remoteSysB.actorSystem, pathEchoC)
      assertEnginesCreated(remoteSysC)
      assertEnginesCreated(remoteSysB)

      // the SSLContext references on sysB should differ
      val after = remoteSysB.sslContextRef.get()
      before shouldNot be(after)
    }

    "keep existing connections alive (no new engines created after cache expiration)" in {
      if (!arteryTcpTlsEnabled())
        pending

      // an initial connection between sysA (from the testkit) and sysB
      // to get sysB up and running
      val (remoteSysB, pathEchoB) = buildRemoteWithEchoActor("B-reused")
      contact(system, pathEchoB)
      assertThreeChannelsAreCreated(remoteSysB)
      // once the three channels are created, no new engines are required... (cont'd)
      contact(system, pathEchoB)
      contact(system, pathEchoB)
      assertNoEnginesCreated(remoteSysB)

      awaitCacheExpiration() // temporal break!

      // ... (cont) even when the cache has expired.
      // Send message to system B from system A should not require a new SSLEngine
      // be created.
      contact(system, pathEchoB)
      assertNoEnginesCreated(remoteSysB)
    }

  }

  // Assert the RemoteSystem created three pairs of SSLEngines (main channel,
  // large messages channel and control channel)
  // NOTE: the large message channel is not enabled but default. In this test suite
  // it's enabled via adding a value to the `large-message-destinations` setting
  private def assertThreeChannelsAreCreated(remoteSystem: RemoteSystem) = {
    assertEnginesCreated(remoteSystem)
    assertEnginesCreated(remoteSystem)
    assertEnginesCreated(remoteSystem)
  }
  private def assertEnginesCreated(remoteSystem: RemoteSystem) = {
    remoteSystem.sslProviderServerProbe.expectMsg("createServerSSLEngine")
    remoteSystem.sslProviderClientProbe.expectMsg("createClientSSLEngine")
  }
  private def assertNoEnginesCreated(remoteSystem: RemoteSystem) = {
    remoteSystem.sslProviderServerProbe.expectNoMessage()
    remoteSystem.sslProviderClientProbe.expectNoMessage()
  }

  // sleep to force the cache in sysB's instance to expire
  private def awaitCacheExpiration(): Unit = {
    Thread.sleep((RotatingKeysSSLEngineProviderSpec.cacheTtlInSeconds + 1) * 1500)
  }

  // Send a message from sourceSystem to targetPath (which should be on another actor system)
  def contact(sourceSystem: ActorSystem, targetPath: ActorPath): Unit = {
    val senderOnSource = TestProbe()(sourceSystem)
    sourceSystem.actorSelection(targetPath).tell(Identify(targetPath.name), senderOnSource.ref)
    val targetRef: ActorRef = senderOnSource.expectMsgType[ActorIdentity].ref.get
    targetRef.tell("ping-1", senderOnSource.ref)
    senderOnSource.expectMsg("ping-1")
  }

  def buildRemoteWithEchoActor(id: String): (RemoteSystem, ActorPath) = {
    val remoteSysB = new RemoteSystem(s"system$id", newRemoteSystem, address)
    val actorName = s"echo$id"
    remoteSysB.actorSystem.actorOf(TestActors.echoActorProps, actorName)
    val pathEchoB = remoteSysB.rootActorPath / "user" / actorName
    (remoteSysB, pathEchoB)
  }

}

class RemoteSystem(
    name: String,
    newRemoteSystem: (Option[String], Option[String], Option[ActorSystemSetup]) => ActorSystem,
    address: (ActorSystem) => Address)(implicit system: ActorSystem) {

  val sslProviderServerProbe: TestProbe = TestProbe()
  val sslProviderClientProbe: TestProbe = TestProbe()
  val sslContextRef = new AtomicReference[SSLContext]()

  val sslProviderSetup =
    SSLEngineProviderSetup(
      sys => new ProbedSSLEngineProvider(sys, sslContextRef, sslProviderServerProbe, sslProviderClientProbe))

  val actorSystem =
    newRemoteSystem(
      Some(RotatingKeysSSLEngineProviderSpec.configStr),
      Some(name),
      Some(ActorSystemSetup(sslProviderSetup)))
  val remoteAddress = address(actorSystem)
  val rootActorPath = RootActorPath(remoteAddress)

}

class ProbedSSLEngineProvider(
    sys: ExtendedActorSystem,
    sslContextRef: AtomicReference[SSLContext],
    sslProviderServerProbe: TestProbe,
    sslProviderClientProbe: TestProbe)
    extends SSLEngineProvider {
  val delegate = new RotatingKeysSSLEngineProvider(sys)

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine = {
    println(s"  -----------------------  creating server engine - ${hostname}:$port  -----------------------==== ")
    sslProviderServerProbe.ref ! "createServerSSLEngine"
    val engine = delegate.createServerSSLEngine(hostname, port)
    // invoked last to let `createEngine` be the trigger of the SSL context reconstruction
    sslContextRef.set(delegate.getSSLContext())
    engine
  }

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine = {
    println(s"  -----------------------  creating client engine - ${hostname}:$port  -----------------------==== ")
    sslProviderClientProbe.ref ! "createClientSSLEngine"
    val engine = delegate.createClientSSLEngine(hostname, port)
    // invoked last to let `createEngine` be the trigger of the SSL context reconstruction
    sslContextRef.set(delegate.getSSLContext())
    engine
  }

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    delegate.verifyClientSession(hostname, session)
  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    delegate.verifyServerSession(hostname, session)
}
