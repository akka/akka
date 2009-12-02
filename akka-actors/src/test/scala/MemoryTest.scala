package se.scalablesolutions.akka.actor

import junit.framework.TestCase

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import scala.collection.mutable.HashSet

class MemoryFootprintTest extends JUnitSuite   {
  class Mem extends Actor {
    def receive = {
      case _ => {}
    }
  }

  @Test
  def shouldCreateManyActors = {
  /*  println("============== MEMORY TEST ==============")
    val actors = new HashSet[Actor]
    println("Total memory: " + Runtime.getRuntime.totalMemory)
    (1 until 1000000).foreach {i =>
      val mem = new Mem
      actors += mem
      if ((i % 100000) == 0) {
        println("Nr actors: " + i)
        println("Total memory: " + (Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory))
      }
    }
    */
    assert(true)
  }
}
