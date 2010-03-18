package se.scalablesolutions.akka.actor

import org.scalatest.Suite
import se.scalablesolutions.akka.util.Logging
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers
import org.junit.{Test}

@RunWith(classOf[JUnitRunner])
class AgentTest extends junit.framework.TestCase 
with Suite with MustMatchers 
with ActorTestUtil with Logging {

  @Test def testAgent = verify(new TestActor {
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
}
