.. _testkit-example:

########################
TestKit Example (Scala)
########################

Ray Roestenburg's example code from `his blog <http://roestenburg.agilesquad.com/2011/02/unit-testing-akka-actors-with-testkit_12.html>`_ adapted to work with Akka 1.1.

.. code-block:: scala

   package unit.akka

   import org.scalatest.matchers.ShouldMatchers
   import org.scalatest.{WordSpec, BeforeAndAfterAll}
   import akka.actor.Actor._
   import akka.util.duration._
   import akka.testkit.TestKit
   import java.util.concurrent.TimeUnit
   import akka.actor.{ActorRef, Actor}
   import util.Random

   /**
    * a Test to show some TestKit examples
    */

   class TestKitUsageSpec extends WordSpec with BeforeAndAfterAll with ShouldMatchers with TestKit {
     val system = ActorSystem()
     import system._
     val echoRef = actorOf(Props(new EchoActor))
     val forwardRef = actorOf(Props(new ForwardingActor(testActor)))
     val filterRef = actorOf(Props(new FilteringActor(testActor)))
     val randomHead = Random.nextInt(6)
     val randomTail = Random.nextInt(10)
     val headList = List().padTo(randomHead, "0")
     val tailList = List().padTo(randomTail, "1")
     val seqRef = actorOf(Props(new SequencingActor(testActor, headList, tailList)))

     override protected def afterAll(): scala.Unit = {
       stopTestActor
       echoRef.stop()
       forwardRef.stop()
       filterRef.stop()
       seqRef.stop()
     }

     "An EchoActor" should {
       "Respond with the same message it receives" in {
         within(100 millis) {
           echoRef ! "test"
           expectMsg("test")
         }
       }
     }
     "A ForwardingActor" should {
       "Forward a message it receives" in {
         within(100 millis) {
           forwardRef ! "test"
           expectMsg("test")
         }
       }
     }
     "A FilteringActor" should {
       "Filter all messages, except expected messagetypes it receives" in {
         var messages = List[String]()
         within(100 millis) {
           filterRef ! "test"
           expectMsg("test")
           filterRef ! 1
           expectNoMsg
           filterRef ! "some"
           filterRef ! "more"
           filterRef ! 1
           filterRef ! "text"
           filterRef ! 1

           receiveWhile(500 millis) {
             case msg: String => messages = msg :: messages
           }
         }
         messages.length should be(3)
         messages.reverse should be(List("some", "more", "text"))
       }
     }
     "A SequencingActor" should {
       "receive an interesting message at some point " in {
         within(100 millis) {
           seqRef ! "something"
           ignoreMsg {
             case msg: String => msg != "something"
           }
           expectMsg("something")
           ignoreMsg {
             case msg: String => msg == "1"
           }
           expectNoMsg
         }
       }
     }
   }

   /**
    * An Actor that echoes everything you send to it
    */
   class EchoActor extends Actor {
     def receive = {
       case msg => {
         self.reply(msg)
       }
     }
   }

   /**
    * An Actor that forwards every message to a next Actor
    */
   class ForwardingActor(next: ActorRef) extends Actor {
     def receive = {
       case msg => {
         next ! msg
       }
     }
   }

   /**
    * An Actor that only forwards certain messages to a next Actor
    */
   class FilteringActor(next: ActorRef) extends Actor {
     def receive = {
       case msg: String => {
         next ! msg
       }
       case _ => None
     }
   }

   /**
    * An actor that sends a sequence of messages with a random head list, an interesting value and a random tail list
    * The idea is that you would like to test that the interesting value is received and that you cant be bothered with the rest
    */
   class SequencingActor(next: ActorRef, head: List[String], tail: List[String]) extends Actor {
     def receive = {
       case msg => {
         head map (next ! _)
         next ! msg
         tail map (next ! _)
       }
     }
   }
