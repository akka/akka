package akka.dispatch

import akka.actor.{ Props, LocalActorRef, Actor }
import akka.testkit.AkkaSpec
import akka.util.Duration
import akka.util.duration._
import akka.testkit.DefaultTimeout
import com.typesafe.config.Config
import annotation.tailrec
import org.scalatest.matchers.{ MustMatchers, ShouldMatchers }

object DaemonicSpec {

  def getConfigStr(daemonOn: Boolean) = {
    " akka { " +
      "\n        actor.default-dispatcher.daemonic = " + { if (daemonOn) "on" else "off" } +
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
  def getAllDefaultDispThreads = {
    getAllThreads.collect {
      case t if t.getName.startsWith("default-dispatcher") ⇒ t
    }.toList
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DaemonicSpec extends org.scalatest.WordSpec with MustMatchers {

  val shutdownWait = 100

  "The default dispatcher" must {
    "Not have any threads running if there is no ActorSystem running" in {
      //The other tests will fail if there are threads from some other test hanging out.
      //This makes sure that if there are any problems with our other tests the problem lies
      //in that test, and not in the environment.
      DaemonicSpec.getAllDefaultDispThreads must be('empty)
    }
    "Not have any threads running after an ActorSystem is shut down" in {
      //This makes sure that we can clear out the threads in between tests
      val system = akka.actor.ActorSystem("shutdownTest")
      system.shutdown
      Thread.sleep(shutdownWait) //shutdown operates async'ly as all things akka, so wait a bit for it to shutdown
      DaemonicSpec.getAllDefaultDispThreads must be('empty)
    }
    "Use daemon threads when configured to do so" in {
      val config = com.typesafe.config.ConfigFactory.parseString(DaemonicSpec.getConfigStr(true))

      val system = akka.actor.ActorSystem("daemonicTest", config)
      try {
        val defaultDispThreads = DaemonicSpec.getAllDefaultDispThreads
        defaultDispThreads must not be ('empty)
        defaultDispThreads foreach (_ must be('daemon))
      } finally {
        system.shutdown
        Thread.sleep(shutdownWait) //wait for shutdown to complete, so as to not interfere with the next test
      }
    }
    "Use non-daemon threads when configured to do so" in {
      val config = com.typesafe.config.ConfigFactory.parseString(DaemonicSpec.getConfigStr(false))

      val system = akka.actor.ActorSystem("daemonicTest", config)
      try {
        val defaultDispThreads = DaemonicSpec.getAllDefaultDispThreads
        defaultDispThreads must not be ('empty)
        //defaultDispThreads foreach (t ⇒ println("Thead(daemon=" + t.isDaemon + "): " + t.getName))
        defaultDispThreads foreach (_ must not be ('daemon))
      } finally {
        system.shutdown
      }
    }
  }
}