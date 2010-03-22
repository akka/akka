package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.actor.Actor.transactor
import se.scalablesolutions.akka.stm.Transaction.atomic
import se.scalablesolutions.akka.util.Logging

import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers

import org.junit.runner.RunWith
import org.junit.{Test}

@RunWith(classOf[JUnitRunner])
class AgentTest extends junit.framework.TestCase 
with Suite with MustMatchers 
with ActorTestUtil with Logging {

  implicit val txFamilyName = "test"

  @Test def testSendFun = verify(new TestActor {
    def test = {
      val agent = Agent(5)
      handle(agent) {
        agent update (_ + 1)
        agent update (_ * 2)

        val result = agent()
        result must be(12)
      }
    }
  })

  @Test def testSendValue = verify(new TestActor {
    def test = {
      val agent = Agent(5)
      handle(agent) {
        agent update 6
        val result = agent()
        result must be(6)
      }
    }
  })

  @Test def testOneAgentUpdateWithinEnlosingTransactionSuccess = {
    case object Go
    val agent = Agent(5)
    val tx = transactor {
      case Go => agent update (_ + 1)
    }
    tx send Go
    Thread.sleep(5000)
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
        agent update (_ * 2)
        try { agent() }
        catch { 
          case _ => latch.countDown 
        }
    }      
    tx send Go
    latch.await // FIXME should await with timeout and fail if timeout
    agent.close
    tx.stop
    assert(true)
  }
}
