package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.actor.Actor.transactor
import se.scalablesolutions.akka.stm.Transaction.Global.atomic
import se.scalablesolutions.akka.util.Logging
import Actor._

import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers

import org.junit.runner.RunWith
import org.junit.Test

import java.util.concurrent.{TimeUnit, CountDownLatch}

@RunWith(classOf[JUnitRunner])
class AgentSpec extends junit.framework.TestCase with Suite with MustMatchers {

  @Test def testSendFun = {
    val agent = Agent(5)
    agent send (_ + 1)
    agent send (_ * 2)
    val result = agent()
    result must be(12)
    agent.close
  }

  @Test def testSendValue = {
    val agent = Agent(5)
    agent send 6
    val result = agent()
    result must be(6)
    agent.close
  }

  @Test def testSendProc = {
    val agent = Agent(5)
    var result = 0
    val latch = new CountDownLatch(2)
    agent sendProc { e => result += e; latch.countDown }
    agent sendProc { e => result += e; latch.countDown }
    assert(latch.await(5, TimeUnit.SECONDS))
    result must be(10)
    agent.close
  }

  @Test def testOneAgentsendWithinEnlosingTransactionSuccess = {
    case object Go
    val agent = Agent(5)
    val latch = new CountDownLatch(1)
    val tx = transactor {
      case Go => agent send { e => latch.countDown; e + 1 }
    }
    tx ! Go
    assert(latch.await(5, TimeUnit.SECONDS))
    val result = agent()
    result must be(6)
    agent.close
    tx.stop
  }

/*
  // Strange test - do we really need it?
  @Test def testDoingAgentGetInEnlosingTransactionShouldYieldException = {
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
    assert(latch.await(5, TimeUnit.SECONDS))
    agent.close
    tx.stop
    assert(true)
  }
*/

  @Test def testAgentForeach = {
    val agent1 = Agent(3)
    var result = 0
    for (first <- agent1) {
      result = first + 1
    }
    result must be(4)
    agent1.close
  }

  @Test def testAgentMap = {
    val agent1 = Agent(3)
    val result = for (first <- agent1) yield first + 1
    result() must be(4)
    result.close
    agent1.close
  }

  @Test def testAgentFlatMap = {
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
}
