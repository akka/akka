/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util
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
import akka.remote.artery.tcp.ssl.FileSystemObserver.CanRead
import akka.remote.artery.tcp.ssl.FileSystemObserver.FileRead
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

import scala.concurrent.{ blocking, Await }
import scala.concurrent.duration._
import scala.util.control.NonFatal

// This is a the real deal Spec. It relies on changing files on a particular folder.
class RotatingProviderWithChangingKeysSpec
    extends RotatingKeysSSLEngineProviderSpec(RotatingKeysSSLEngineProviderSpec.tempFileConfig) {
  import RotatingKeysSSLEngineProviderSpec._

  protected override def atStartup(): Unit = {
    super.atStartup()
    deployCaCert()
    deployKeySet("ssl/artery-nodes/artery-node001.example.com")()
  }

  "Artery with TLS/TCP with RotatingKeysSSLEngine" must {
    "rebuild the SSLContext using new keys" in {
      if (!arteryTcpTlsEnabled())
        pending

      // an initial connection between sysA (from the testkit) and sysB
      // to get sysB up and running
      val (remoteSysB, pathEchoB) = buildRemoteWithEchoActor("B-reread")
      contact(system, pathEchoB)
      assertEnginesCreated(remoteSysB)
      val before = remoteSysB.sslContextRef.get()

      // setup new (invalid) keys
      // The `ssl/rsa-client.example.com` keyset can't be used in peer-to-peer connections
      // it's only valid for `clientAuth`

      deployKeySet("ssl/rsa-client.example.com")(system, remoteSysB.actorSystem)
      awaitCacheExpiration()
      val (remoteSysC, pathEchoC) = buildRemoteWithEchoActor("C-reread")
      try {
        contact(remoteSysB.actorSystem, pathEchoC)
        fail("The credentials under `ssl/rsa-client` are not valid for Akka remote so contact() must fail.")
      } catch {
        case _: java.lang.AssertionError =>
      }

      // deploy a new key set
      deployKeySet("ssl/artery-nodes/artery-node003.example.com")(
        system,
        remoteSysB.actorSystem,
        remoteSysC.actorSystem)

      // Send message to system C from system B.
      // Using invalid keys, this should fail
      val (remoteSysD, pathEchoD) = buildRemoteWithEchoActor("D-reread")
      contact(remoteSysB.actorSystem, pathEchoD)
      assertEnginesCreated(remoteSysB)
      assertEnginesCreated(remoteSysD)
      // the SSLContext references on sysB should differ
      val after = remoteSysB.sslContextRef.get()
      before shouldNot be(after)
    }

  }
}

// This is a simplification Spec. It doesn't rely on changing files.
class RotatingProviderWithStaticKeysSpec
    extends RotatingKeysSSLEngineProviderSpec(RotatingKeysSSLEngineProviderSpec.resourcesConfig) {
  "Artery with TLS/TCP with RotatingKeysSSLEngine" must {

    "rebuild the SSLContext" in {
      if (!arteryTcpTlsEnabled())
        pending

      // an initial connection between sysA (from the testkit) and sysB
      // to get sysB up and running
      val (remoteSysB, pathEchoB) = buildRemoteWithEchoActor("B-rebuild")
      contact(system, pathEchoB)
      assertEnginesCreated(remoteSysB)
      val before = remoteSysB.sslContextRef.get()

      awaitCacheExpiration()

      // Send message to system C from system B.
      // Not using system A because we can't get a reference to the SSLContext in system A
      val (remoteSysC, pathEchoC) = buildRemoteWithEchoActor("C-rebuild")
      contact(remoteSysB.actorSystem, pathEchoC)
      assertEnginesCreated(remoteSysC)
      assertEnginesCreated(remoteSysB)

      // the SSLContext references on sysB should differ
      val after = remoteSysB.sslContextRef.get()
      before shouldNot be(after)
    }

    "keep existing connections alive (no new SSLEngine's created after cache expiration)" in {
      if (!arteryTcpTlsEnabled())
        pending

      // an initial connection between sysA (from the testkit) and sysB
      // to get sysB up and running
      val (remoteSysB, pathEchoB) = buildRemoteWithEchoActor("B-reuse-alive")
      contact(system, pathEchoB)
      assertThreeChannelsAreCreated(remoteSysB)
      // once the three channels are created, no new engines are required... (cont'd)
      contact(system, pathEchoB)
      contact(system, pathEchoB)
      assertNoEnginesCreated(remoteSysB)

      awaitCacheExpiration()

      // ... (cont) even when the cache has expired.
      // Send message to system B from system A should not require a new SSLEngine
      // be created.
      contact(system, pathEchoB)
      assertNoEnginesCreated(remoteSysB)
    }

  }
}

// This is an integration tests specifically to test key rotation. Basic happy-path
// integration of RotatingKeysSSLEngineSpec as an SSLEngineProvider for Akka Remote
// is tested in `TlsTcpWithRotatingKeysSSLEngineSpec`
object RotatingKeysSSLEngineProviderSpec {
  val cacheTtlInSeconds = 1

  private val arteryNode001Id = "ssl/artery-nodes/artery-node001.example.com"

  private val baseConfig = """
      akka.remote.artery {
        ## the large-messages channel in artery is not used for this tests 
        ## but we're enabling it to test it also creates its own SSLEngine 
        large-message-destinations = [ "/user/large" ]
      }
      akka.remote.artery.ssl {
        ssl-engine-provider = akka.remote.artery.tcp.ssl.RotatingKeysSSLEngineProvider
      }
    """

  val resourcesConfig: String = baseConfig +
    s"""
      akka.remote.artery.ssl.rotating-keys-engine {
        key-file = ${getClass.getClassLoader.getResource(s"$arteryNode001Id.pem").getPath}
        cert-file = ${getClass.getClassLoader.getResource(s"$arteryNode001Id.crt").getPath}
        ca-cert-file = ${getClass.getClassLoader.getResource("ssl/exampleca.crt").getPath}
        ssl-context-cache-ttl = ${cacheTtlInSeconds}s
      }
    """

  val temporaryDirectory: Path = Files.createTempDirectory("akka-remote-rotating-keys-spec")
  val keyLocation = new File(temporaryDirectory.toFile, "tls.key")
  val certLocation = new File(temporaryDirectory.toFile, "tls.crt")
  val cacertLocation = new File(temporaryDirectory.toFile, "ca.crt")
  val tempFileConfig: String = baseConfig +
    s"""
      akka.remote.artery.ssl.rotating-keys-engine {
        secret-mount-point = ${temporaryDirectory.toFile.getAbsolutePath}
        key-file = $${akka.remote.artery.ssl.rotating-keys-engine.secret-mount-point}/tls.key
        cert-file = $${akka.remote.artery.ssl.rotating-keys-engine.secret-mount-point}/tls.crt
        ca-cert-file = $${akka.remote.artery.ssl.rotating-keys-engine.secret-mount-point}/ca.crt
        ssl-context-cache-ttl = ${cacheTtlInSeconds}s
      }
    """

  private def deployResource(resourceName: String, to: Path): Unit = blocking {
    // manually ensuring files are deleted and copied to prevent races.
    try {
      ensureDeleted(to)
      val from = new File(getClass.getClassLoader.getResource(resourceName).getPath).toPath
      Files.copy(from, to)
      ensureCopied(from, to)
    } catch {
      case NonFatal(t) => throw new RuntimeException(s"Can't copy resource [$resourceName] to [$to].", t)
    }
  }

  def ensureDeleted(to: Path): Unit = blocking {
    do {
      to.toFile.delete()
    } while (to.toFile.exists())
  }
  def ensureCopied(resourcePath: Path, to: Path): Unit = blocking {
    var equal = false
    do {
      equal = util.Arrays.equals(Files.readAllBytes(resourcePath), Files.readAllBytes(to))
    } while (!equal)
  }
  private def ensureVisible(actorSystem: ActorSystem): Unit = {
    ensureVisible(actorSystem, certLocation.toPath)
    ensureVisible(actorSystem, keyLocation.toPath)
  }
  private def ensureVisible(actorSystem: ActorSystem, path: Path): Unit = {
    val readProbe = TestProbe()(actorSystem)
    val ref = actorSystem.actorOf(FileSystemObserver.props)
    ref.tell(CanRead(path.toFile.getAbsolutePath), readProbe.ref)
    readProbe.expectMsg(FileRead)
  }
  def deployCaCert(): Unit = {
    deployResource("ssl/exampleca.crt", cacertLocation.toPath)
  }
  def deployKeySet(setName: String)(actorSys: ActorSystem*): Unit = {
    deployResource(setName + ".crt", certLocation.toPath)
    deployResource(setName + ".pem", keyLocation.toPath)
    actorSys.foreach(ensureVisible)
  }
  def cleanupTemporaryDirectory(): Unit = {
    temporaryDirectory.toFile.listFiles().foreach { _.delete() }
    temporaryDirectory.toFile.delete()
  }
}

// In this test each system reads keys/certs from a different temporal folder to control
// which system gets keys rotated.
abstract class RotatingKeysSSLEngineProviderSpec(extraConfig: String)
    extends ArteryMultiNodeSpec(ConfigFactory.parseString(extraConfig).withFallback(TlsTcpSpec.config))
    with ImplicitSender {
  import RotatingKeysSSLEngineProviderSpec._

  var systemsToTerminate: Seq[ActorSystem] = Nil

  // Assert the RemoteSystem created three pairs of SSLEngines (main channel,
  // large messages channel and control channel)
  // NOTE: the large message channel is not enabled but default. In this test suite
  // it's enabled via adding a value to the `large-message-destinations` setting
  def assertThreeChannelsAreCreated(remoteSystem: RemoteSystem) = {
    assertEnginesCreated(remoteSystem)
    assertEnginesCreated(remoteSystem)
    assertEnginesCreated(remoteSystem)
  }
  def assertEnginesCreated(remoteSystem: RemoteSystem) = {
    remoteSystem.sslProviderServerProbe.expectMsg("createServerSSLEngine")
    remoteSystem.sslProviderClientProbe.expectMsg("createClientSSLEngine")
  }
  def assertNoEnginesCreated(remoteSystem: RemoteSystem) = {
    remoteSystem.sslProviderServerProbe.expectNoMessage()
    remoteSystem.sslProviderClientProbe.expectNoMessage()
  }

  // sleep to force the cache in sysB's instance to expire
  def awaitCacheExpiration(): Unit = {
    Thread.sleep((RotatingKeysSSLEngineProviderSpec.cacheTtlInSeconds + 1) * 1000)
  }

  def contact(fromSystem: ActorSystem, toPath: ActorPath): Unit = {
    val senderOnSource = TestProbe()(fromSystem)
    fromSystem.actorSelection(toPath).tell(Identify(toPath.name), senderOnSource.ref)
    val targetRef: ActorRef = senderOnSource.expectMsgType[ActorIdentity].ref.get
    targetRef.tell("ping-1", senderOnSource.ref)
    senderOnSource.expectMsg("ping-1")
  }

  def buildRemoteWithEchoActor(id: String): (RemoteSystem, ActorPath) = {
    val remoteSysB = new RemoteSystem(s"system$id", extraConfig, newRemoteSystem, address)
    systemsToTerminate :+= remoteSysB.actorSystem
    val actorName = s"echo$id"
    remoteSysB.actorSystem.actorOf(TestActors.echoActorProps, actorName)
    val pathEchoB = remoteSysB.rootActorPath / "user" / actorName
    (remoteSysB, pathEchoB)
  }

  override def afterTermination(): Unit = {
    systemsToTerminate.foreach { systemToTerminate =>
      system.log.info(s"Terminating $systemToTerminate...")
      Await.result(systemToTerminate.terminate(), 10.seconds)
    }
    cleanupTemporaryDirectory()
    super.afterTermination()
  }
}

class RemoteSystem(
    name: String,
    configString: String,
    newRemoteSystem: (Option[String], Option[String], Option[ActorSystemSetup]) => ActorSystem,
    address: (ActorSystem) => Address)(implicit system: ActorSystem) {

  val sslProviderServerProbe: TestProbe = TestProbe()
  val sslProviderClientProbe: TestProbe = TestProbe()
  val sslContextRef = new AtomicReference[SSLContext]()

  val sslProviderSetup =
    SSLEngineProviderSetup(
      sys => new ProbedSSLEngineProvider(sys, sslContextRef, sslProviderServerProbe, sslProviderClientProbe))

  val actorSystem =
    newRemoteSystem(Some(configString), Some(name), Some(ActorSystemSetup(sslProviderSetup)))
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
    val engine = delegate.createServerSSLEngine(hostname, port)
    // only report the invocation on the probe when the invocation succeeds
    sslProviderServerProbe.ref ! "createServerSSLEngine"
    // invoked last to let `createEngine` be the trigger of the SSL context reconstruction
    sslContextRef.set(delegate.getSSLContext())
    engine
  }

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine = {
    val engine = delegate.createClientSSLEngine(hostname, port)
    // only report the invocation on the probe when the invocation succeeds
    sslProviderClientProbe.ref ! "createClientSSLEngine"
    // invoked last to let `createEngine` be the trigger of the SSL context reconstruction
    sslContextRef.set(delegate.getSSLContext())
    engine
  }

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    delegate.verifyClientSession(hostname, session)
  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    delegate.verifyServerSession(hostname, session)
}
