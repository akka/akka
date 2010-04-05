package se.scalablesolutions.akka.actor

import _root_.java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.Actor.transactor
import se.scalablesolutions.akka.stm.Transaction.Global.atomic
import se.scalablesolutions.akka.util.Logging

import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers

import org.junit.runner.RunWith
import org.junit.{Test}

@RunWith(classOf[JUnitRunner])
class AgentSpec extends junit.framework.TestCase 
with Suite with MustMatchers 
with ActorTestUtil with Logging {

  @Test def testSendFun = verify(new TestActor {
    def test = {
      val agent = Agent(5)
      handle(agent) {
        agent send (_ + 1)
        agent send (_ * 2)
        val result = agent()
        result must be(12)
      }
    }
  })

  @Test def testSendValue = verify(new TestActor {
    def test = {
      val agent = Agent(5)
      handle(agent) {
        agent send 6
        val result = agent()
        result must be(6)
      }
    }
  })

  @Test def testSendProc = verify(new TestActor {
    def test = {
      val agent = Agent(5)
      var result = 0
      handle(agent) {
        agent sendProc (result += _)
        agent sendProc (result += _)
        Thread.sleep(1000)
        result must be(10)
      }
    }
  })

  @Test def testOneAgentsendWithinEnlosingTransactionSuccess = {
    case object Go
    val agent = Agent(5)
    val tx = transactor {
      case Go => agent send (_ + 1)
    }
    tx ! Go
    Thread.sleep(1000)
    val result = agent()
    result must be(6)
    agent.close
    tx.stop
  }

  @Test def testDoingAgentGetInEnlosingTransactionShouldYieldException = {
    import java.util.concurrent.CountDownLatch
    case object Go
    val latch = new CountDownLatch(1)
    val agent = Agent(5)
    val tx = transactor {
      case Go => 
        agent send (_ * 2)
        try { agent() }
        catch { 
          case _ => latch.countDown 
        }
    }      
    tx ! Go
    assert(latch.await(1, TimeUnit.SECONDS))
    agent.close
    tx.stop
    assert(true)
  }

  @Test def testAgentForeach = verify(new TestActor {
    def test = {
      val agent1 = Agent(3)
      var result = 0
      for (first <- agent1) {
        result = first + 1 
      }
      result must be(4)
      agent1.close
    }
  })

  @Test def testAgentMap = verify(new TestActor {
    def test = {
      val agent1 = Agent(3)
      val result = for (first <- agent1) yield first + 1 
      result() must be(4)
      result.close
      agent1.close
    }
  })

  @Test def testAgentFlatMap = verify(new TestActor {
    def test = {
      val agent1 = Agent(3)
      val agent2 = Agent(5)
      val result = for {
        first <- agent1
        second <- agent2
      } yield second + first 
      result() must be(8)
      result.close
      agent1.close
      agent2.close
    }
  })
}
