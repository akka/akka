package in.nene.d.grafverse

import in.nene.d.grafverse.core._
import scala.actors._
import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.stm.local._


object Tester {
	class SetMutator(name: String) extends Actor {
		def act() {
			react {
				case("ADD",set: TransactionalSet[Long],value: Long, delay: Int) =>
					atomic {
						set += value
						Thread.sleep(delay)
						println("Exiting " + name + " " + set.size)
					}
				case("DEL",set: TransactionalSet[Long],value: Long, delay: Int) =>
					atomic {
						set -= value
						Thread.sleep(delay)
						println("Exiting " + name + " " + set.size)
					}
				case msg =>
					println("Unhandled message: " + msg)
			}
		}
	}
	class MapMutator() extends Actor {
		def act() {
			react {
				case("ADD",map: TransactionalMap[Long,String],key: Long, value: String) =>
					atomic {
						map += key -> value
						Thread.sleep(5000)
						println("Exiting " + map.size)
					}
				case("DEL",map: TransactionalMap[Long,String],key: Long) =>
					atomic {
						map -= key
						Thread.sleep(5000)
						println("Exiting " + map.size)
					}
				case msg =>
					println("Unhandled message: " + msg)
			}
		}
	}
	def main(args: Array[String]) {
		testAddSet()
//		testAddMap()
	}
	
	def testAddSet() {
		val actor1 = new SetMutator("One")
		val actor2 = new SetMutator("Two")
		
		actor1.start()
		actor2.start()
		var set = TransactionalSet[Long](1L,2L,3L)
		actor1 ! (("ADD", set, 4L, 2000))
		Thread.sleep(500)
		actor2 ! (("DEL", set, 4L, 2000))
		Thread.sleep(5000)
		println("Size:" + set.size)
		for (value <- set) println(value)
	}
	def testAddMap() {
		val actor1 = new MapMutator()
		val actor2 = new MapMutator()
		actor1.start()
		actor2.start()
		var map = TransactionalMap[Long,String](1L->"one",2L->"two",3L->"three")
		actor1 ! (("ADD", map, 4L, "four"))
		Thread.sleep(1000)
		actor2 ! (("ADD", map, 5L, "five"))
		Thread.sleep(15000)
		println("Size:" + map.size)
		for ((key,value) <- map) println(key + " -> " + value)
	}
}