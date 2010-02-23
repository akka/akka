package se.scalablesolutions.akka.actor

import org.scalatest.Suite
import se.scalablesolutions.akka.util.Logging
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers
import org.junit.{Test}

/*
@RunWith(classOf[JUnitRunner])
class AgentTest extends junit.framework.TestCase with Suite with MustMatchers with ActorTestUtil with Logging {

  @Test def testAgent = verify(new TestActor {
    def test = {
      atomic {
        val t = Agent(5)
        handle(t) {
          t.update(_ + 1)
          t.update(_ * 2)

          val r = t()
          r must be(12)
        }
      }
    }
  })
}
*/