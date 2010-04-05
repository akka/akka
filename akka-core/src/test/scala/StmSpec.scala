package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.stm.Transaction.Local._
import se.scalablesolutions.akka.stm._

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class StmSpec extends 
  Spec with 
  ShouldMatchers with 
  BeforeAndAfterAll {
  
  describe("STM outside actors") {
    it("should be able to do multiple consecutive atomic {..} statements") {

      lazy val ref = TransactionalState.newRef[Int]

      def increment = atomic {
        ref.swap(ref.get.getOrElse(0) + 1)
      }

      def total: Int = atomic {
        ref.get.getOrElse(0)
      }

      increment
      increment
      increment
      total should equal(3)
    }

    it("should be able to do nested atomic {..} statements") {

      lazy val ref = TransactionalState.newRef[Int]

      def increment = atomic {
        ref.swap(ref.get.getOrElse(0) + 1)
      }
      def total: Int = atomic {
        ref.get.getOrElse(0)
      }
      
      atomic {
        increment
        increment        
      }
      atomic {
        increment
        total should equal(3)
      }
    }

    it("should roll back failing nested atomic {..} statements") {

      lazy val ref = TransactionalState.newRef[Int]

      def increment = atomic {
        ref.swap(ref.get.getOrElse(0) + 1)
      }
      def total: Int = atomic {
        ref.get.getOrElse(0)
      }
      try {
        atomic {
          increment
          increment
          throw new Exception
        }        
      } catch {
        case e => {}
      }
      total should equal(0)
    }
  }
}
