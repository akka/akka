package akka.remote

import annotation.tailrec
import org.scalatest.matchers.MustMatchers

/**
 * A nearly exact copy of a test in akka-actor-tests. This can't be there
 * because if run there we would get <code>java.lang.ClassNotFoundException: akka.remote.RemoteActorRefProvider</code>
 */
object DaemonicSpec {

  private def getConfigStr(daemonOn: Boolean) = {

    " akka { " +
      "\n  actor.provider = \"akka.remote.RemoteActorRefProvider\" " +
      "\n  remote.transport = \"akka.remote.netty.NettyRemoteSupport\" " +
      "\n  remote.daemonic = " + { if (daemonOn) "on" else "off" } +
      "\n    }"
  }
  @tailrec
  private def getRoot(tg: ThreadGroup): ThreadGroup = {
    val parent = tg.getParent
    if (parent == null)
      tg
    else
      getRoot(parent)
  }
  private def getRoot: ThreadGroup = {
    getRoot(Thread.currentThread.getThreadGroup)
  }
  private def getAllThreads = {
    val rootTg = getRoot
    val numThreads = rootTg.activeCount()
    val ta = new Array[Thread](numThreads + 2);
    val taSize = rootTg.enumerate(ta);
    ta.collect({ case a if a != null ⇒ a })
  }
  private def getAllDispThreads = {
    val prefixes = List("New I/O server boss", "akka.remote.network-event-sender-dispatcher", "NettyRemoteSupport")
    getAllThreads.collect {
      case t if prefixes.exists(prefix ⇒ t.getName.startsWith(prefix)) ⇒ t
    }.toList
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DaemonicSpec extends org.scalatest.WordSpec with MustMatchers {

  val shutdownWait = 100
  /** Right now this test is failing. When it is fixed turn this off */
  val printAllThreads = true

  "The remote dispatcher" must {
    "Not have any threads running if there is no ActorSystem running" in {
      //The other tests will fail if there are threads from some other test hanging out.
      //This makes sure that if there are any problems with our other tests the problem lies
      //in that test, and not in the environment.
      DaemonicSpec.getAllDispThreads must be('empty)
    }
    "Not have any threads running after an ActorSystem is shut down" in {
      //This makes sure that we can clear out the threads in between tests
      val config = com.typesafe.config.ConfigFactory.parseString(DaemonicSpec.getConfigStr(false))
      val system = akka.actor.ActorSystem("shutdownTest", config)
      system.shutdown()
      Thread.sleep(shutdownWait) //shutdown operates async'ly as all things akka, so wait a bit for it to shutdown
      DaemonicSpec.getAllDispThreads must be('empty)
    }
    "Use daemon threads when configured to do so" in {
      val config = com.typesafe.config.ConfigFactory.parseString(DaemonicSpec.getConfigStr(true))

      val system = akka.actor.ActorSystem("daemonicTest", config)
      try {
        Thread.sleep(shutdownWait) //wait for netty to start up
        val defaultDispThreads = DaemonicSpec.getAllDispThreads
        if (printAllThreads)
          DaemonicSpec.getAllThreads foreach (t ⇒ println("1) Thead(daemon=" + t.isDaemon + "): " + t.getName))
        defaultDispThreads must not be ('empty)
        defaultDispThreads foreach (_ must be('daemon))
      } finally {
        system.shutdown()
        Thread.sleep(shutdownWait) //wait for shutdown to complete, so as to not interfere with the next test
      }
    }
    "Use non-daemon threads when configured to do so" in {
      val config = com.typesafe.config.ConfigFactory.parseString(DaemonicSpec.getConfigStr(false))

      val system = akka.actor.ActorSystem("daemonicTest", config)
      try {
        Thread.sleep(shutdownWait) //wait for netty to start up
        val defaultDispThreads = DaemonicSpec.getAllDispThreads
        if (printAllThreads)
          DaemonicSpec.getAllThreads foreach (t ⇒ println("2) Thead(daemon=" + t.isDaemon + "): " + t.getName))
        defaultDispThreads must not be ('empty)
        defaultDispThreads foreach (_ must not be ('daemon))
      } finally {
        system.shutdown()
      }
    }
  }
}