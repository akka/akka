package se.scalablesolutions.akka.patterns

import config.ScalaConfig._

import org.scalatest.Suite
import patterns.Patterns
import se.scalablesolutions.akka.util.Logging
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers
import org.junit.{Before, After, Test}
import scala.collection.mutable.HashSet

@RunWith(classOf[JUnitRunner])
class ActorPatternsTest extends junit.framework.TestCase with Suite with MustMatchers with ActorTestUtil with Logging {
  import Actor._
  import Patterns._
  @Test def testDispatcher = verify(new TestActor {
     def test = {
      val (testMsg1,testMsg2,testMsg3,testMsg4) = ("test1","test2","test3","test4")

      var targetOk = 0
      val t1: Actor = actor {
        case `testMsg1` => targetOk += 2
        case `testMsg2` => targetOk += 4
      }

      val t2: Actor = actor {
          case `testMsg3` => targetOk += 8
      }

      val d = dispatcherActor {
        case `testMsg1`|`testMsg2` => t1
        case `testMsg3` => t2
      }
      
      handle(d,t1,t2){
        d ! testMsg1
        d ! testMsg2
        d ! testMsg3
        Thread.sleep(1000)
        targetOk must be(14)
      }
     }
  })

  @Test def testLogger = verify(new TestActor {
    def test = {
      val msgs = new HashSet[Any]
      val t1: Actor = actor {
        case _ =>
      }
      val l = loggerActor(t1,(x) => msgs += x)
      handle(t1,l) {
        val t1 : Any = "foo"
        val t2 : Any = "bar"
        l ! t1
        l ! t2
        Thread.sleep(1000)
        msgs must ( have size (2) and contain (t1) and contain (t2) )
      }
    }
  })
}

trait ActorTestUtil {

  def handle[T](actors : Actor*)(test : => T) : T = {
    for(a <- actors) a.start
    try {
      test
    }
    finally {
      for(a <- actors) a.stop 
    }
  }
  
  def verify(actor : TestActor) : Unit = handle(actor) {
    actor.test
  }
}

abstract class TestActor extends Actor with ActorTestUtil {
  def test : Unit
  def receive = { case _ =>  }
}