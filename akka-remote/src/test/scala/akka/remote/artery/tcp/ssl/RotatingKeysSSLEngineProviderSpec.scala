/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorIdentity
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
    "rotate keys and rebuild the SSLContext" in {
      if (!arteryTcpTlsEnabled())
        pending

      // an initial connection between sysA (from the testkit) and sysB
      // to get sysB up and running
      val remoteSysB = new RemoteSystem("systemB", newRemoteSystem, address)
      remoteSysB.actorSystem.actorOf(TestActors.echoActorProps, "echoB")
      val pathEchoB = remoteSysB.rootActorPath / "user" / "echoB"

      val senderOnA = TestProbe()(system)
      system.actorSelection(pathEchoB).tell(Identify(pathEchoB.name), senderOnA.ref)
      val echoBRef: ActorRef = senderOnA.expectMsgType[ActorIdentity].ref.get
      echoBRef.tell("ping-1", senderOnA.ref)
      senderOnA.expectMsg("ping-1")

      remoteSysB.sslProviderServerProbe.expectMsg("createServerSSLEngine")
      remoteSysB.sslProviderClientProbe.expectMsg("createClientSSLEngine")
      val before = remoteSysB.sslContextRef.get()

      // sleep to force the cache in sysB's instance to expire
      Thread.sleep((RotatingKeysSSLEngineProviderSpec.cacheTtlInSeconds + 1) * 1000)
      // Connect system C to system B because I can't get a reference to the SSLContext in system A
      val remoteSysC = new RemoteSystem("systemC", newRemoteSystem, address)
      remoteSysC.actorSystem.actorOf(TestActors.echoActorProps, "echoC")
      val pathEchoC = remoteSysC.rootActorPath / "user" / "echoC"

      val senderOnB = TestProbe()(remoteSysB.actorSystem)
      remoteSysB.actorSystem.actorSelection(pathEchoC).tell(Identify(pathEchoC.name), senderOnB.ref)
      val echoCRef: ActorRef = senderOnB.expectMsgType[ActorIdentity].ref.get
      echoCRef.tell("ping-1", senderOnB.ref)
      senderOnB.expectMsg("ping-1")

      // Expect both sysB and sysC had seldom requests for connections.
      remoteSysC.sslProviderServerProbe.expectMsg("createServerSSLEngine")
      remoteSysC.sslProviderClientProbe.expectMsg("createClientSSLEngine")
      remoteSysB.sslProviderServerProbe.expectMsg("createServerSSLEngine")
      remoteSysB.sslProviderClientProbe.expectMsg("createClientSSLEngine")

      // the SSLContext references on sysB should differ
      val after = remoteSysB.sslContextRef.get()
      before shouldNot be(after)

    }

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
    sslProviderServerProbe.ref ! "createServerSSLEngine"
    val engine = delegate.createServerSSLEngine(hostname, port)
    // invoke after to let `createEngine` trigger the SSL context reconstruction
    sslContextRef.set(delegate.getSSLContext())
    engine
  }

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine = {
    sslProviderClientProbe.ref ! "createClientSSLEngine"
    val engine = delegate.createClientSSLEngine(hostname, port)
    // invoke after to let `createEngine` trigger the SSL context reconstruction
    sslContextRef.set(delegate.getSSLContext())
    engine
  }

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    delegate.verifyClientSession(hostname, session)
  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    delegate.verifyServerSession(hostname, session)
}
