package akka.typed.actor

import akka.actor.TypedActor
import org.scalatest.{ WordSpec }
import org.scalatest.matchers.{ MustMatchers }
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

object Ticket552Spec {

trait Base {
 val id: String
 def getSomething(key: String): String = id + key
}

trait MyBaseOne extends Base {
 val id: String = "myBaseOne "
 def getSomethingDifferent(key: Int): Int
}

trait MyBaseTwo extends Base {
 val id: String = "myBaseTwo "
}

class MyBaseOneImpl extends TypedActor with MyBaseOne {
 override def getSomethingDifferent(key: Int): Int = key + 2
}

class MyBaseTwoImpl extends TypedActor with MyBaseTwo

}

@RunWith(classOf[JUnitRunner])
class Ticket552Spec extends WordSpec with MustMatchers {
  import Ticket552Spec._

 "TypedActor" should {

   "return int" in {
     val myBaseOneActor: MyBaseOne =
       TypedActor.newInstance[MyBaseOne](classOf[MyBaseOne],
                                         classOf[MyBaseOneImpl],3000)

     try {
       myBaseOneActor.getSomethingDifferent(5) must be === 7
     } catch {
       case e: Exception => println(e.toString)
     } finally {
       TypedActor.stop(myBaseOneActor)
     }
   }

   "return string" in {
     val myBaseOneActor: Base =
       TypedActor.newInstance[Base](classOf[Base],
                                         classOf[MyBaseOneImpl],3000)

     try {
       myBaseOneActor.getSomething("hello") must be === "myBaseOne hello"
     } catch {
       case e: Exception => println(e.toString)
     } finally {
       TypedActor.stop(myBaseOneActor)
     }
   }

   "fail for myBaseTwo" in {
     val myBaseTwoActor: MyBaseTwo =
       TypedActor.newInstance[MyBaseTwo](classOf[MyBaseTwo],
                                         classOf[MyBaseTwoImpl],3000)

     try {
       intercept[java.lang.AbstractMethodError] {
         myBaseTwoActor.getSomething("hello")
       }
     } catch {
       case e: java.lang.AbstractMethodError => e.printStackTrace
     }
     finally {
       TypedActor.stop(myBaseTwoActor)
     }
   }

   "fail for myBaseOne inherited method" in {
     val myBaseOneActor: MyBaseOne =
       TypedActor.newInstance[MyBaseOne](classOf[MyBaseOne],
                                         classOf[MyBaseOneImpl],3000)

     try {
       intercept[java.lang.AbstractMethodError] {
         myBaseOneActor.getSomething("hello")
       }
     } catch {
       case e: java.lang.AbstractMethodError => e.printStackTrace
     } finally {
       TypedActor.stop(myBaseOneActor)
     }
   }

 }
}

