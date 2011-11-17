// /**
//  * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
//  */

// //#imports
// package akka.tutorial.first.scala

// import akka.actor.{ Actor, ActorSystem, PoisonPill }
// import akka.routing.Routing.Broadcast
// import akka.routing.{ RoutedProps, Routing }
// import java.util.concurrent.CountDownLatch
// //#imports

// //#system
// object Pi extends App {

//   val system = ActorSystem()

//   calculate(nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)

//   //#actors-and-messages
//   // ====================
//   // ===== Messages =====
//   // ====================
//   //#messages
//   sealed trait PiMessage

//   case object Calculate extends PiMessage

//   case class Work(start: Int, nrOfElements: Int) extends PiMessage

//   case class Result(value: Double) extends PiMessage
//   //#messages

//   // ==================
//   // ===== Worker =====
//   // ==================
//   //#worker
//   class Worker extends Actor {

//     // define the work
//     //#calculatePiFor
//     def calculatePiFor(start: Int, nrOfElements: Int): Double = {
//       var acc = 0.0
//       for (i ← start until (start + nrOfElements))
//         acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1)
//       acc
//     }
//     //#calculatePiFor

//     def receive = {
//       case Work(start, nrOfElements) ⇒ sender ! Result(calculatePiFor(start, nrOfElements)) // perform the work
//     }
//   }
//   //#worker

//   // ==================
//   // ===== Master =====
//   // ==================
//   //#master
//   class Master(nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, latch: CountDownLatch) extends Actor {

//     var pi: Double = _
//     var nrOfResults: Int = _
//     var start: Long = _

//     //#create-workers
//     // create the workers
//     val workers = Vector.fill(nrOfWorkers)(system.actorOf[Worker])

//     // wrap them with a load-balancing router
//     val router = system.actorOf(RoutedProps().withRoundRobinRouter.withLocalConnections(workers), "pi")
//     //#create-workers

//     //#master-receive
//     // message handler
//     def receive = {
//       //#handle-messages
//       case Calculate ⇒
//         // schedule work
//         for (i ← 0 until nrOfMessages) router ! Work(i * nrOfElements, nrOfElements)

//         // send a PoisonPill to all workers telling them to shut down themselves
//         router ! Broadcast(PoisonPill)

//         // send a PoisonPill to the router, telling him to shut himself down
//         router ! PoisonPill

//       case Result(value) ⇒
//         // handle result from the worker
//         pi += value
//         nrOfResults += 1
//         if (nrOfResults == nrOfMessages) self.stop()
//       //#handle-messages
//     }
//     //#master-receive

//     override def preStart() {
//       start = System.currentTimeMillis
//     }

//     override def postStop() {
//       // tell the world that the calculation is complete
//       println(
//         "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis"
//           .format(pi, (System.currentTimeMillis - start)))
//       latch.countDown()
//     }
//   }
//   //#master
//   //#actors-and-messages

//   // ==================
//   // ===== Run it =====
//   // ==================
//   def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {

//     // this latch is only plumbing to know when the calculation is completed
//     val latch = new CountDownLatch(1)

//     // create the master
//     val master = system.actorOf(new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch))

//     // start the calculation
//     master ! Calculate

//     // wait for master to shut down
//     latch.await()
//   }
// }
// //#system

