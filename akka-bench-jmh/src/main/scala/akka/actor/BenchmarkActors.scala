/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.Random

object BenchmarkActors {

  val timeout = 30.seconds

  case object Message
  case object Stop

  case class Request(userId: Int)
  case object StopUserService

  case class User(userId: Int, firstName: String, lastName: String, ssn: Int, friends: Seq[Int])

  class UserServiceActor(userDB: Map[Int, User], latch: CountDownLatch, numQueries: Int) extends Actor {

    var left = numQueries

    def receive = {
      case Request(id) =>
        userDB.get(id).foreach(u => sender() ! u)

        if (left == 0) {
          latch.countDown()
          context stop self
        }
        left -= 1
    }

  }

  object UserServiceActor {

    def props(numQueriesForActor: Int, numUsersInDB: Int, latch: CountDownLatch, randomGen: Random) = {
      val users = for {
        id <- 0 until numUsersInDB
        firstName = randomGen.nextString(5)
        lastName = randomGen.nextString(7)
        ssn = randomGen.nextInt()
        friendIds = for { _ <- 0 until 5 } yield randomGen.nextInt(numUsersInDB)
      } yield id -> User(id, firstName, lastName, ssn, friendIds)
      Props(new UserServiceActor(users.toMap, latch, numQueriesForActor))
    }
  }

  class PingPong(val messages: Int, latch: CountDownLatch) extends Actor {
    var left = messages / 2
    def receive = {
      case Message =>

        if (left == 0) {
          latch.countDown()
          context stop self
        }

        sender() ! Message
        left -= 1
    }
  }

  object PingPong {
    def props(messages: Int, latch: CountDownLatch) = Props(new PingPong(messages, latch))
  }

  class Pipe(next: Option[ActorRef]) extends Actor {
    def receive = {
      case Message =>
        if (next.isDefined) next.get forward Message
      case Stop =>
        context stop self
        if (next.isDefined) next.get forward Stop
    }
  }

  object Pipe {
    def props(next: Option[ActorRef]) = Props(new Pipe(next))
  }

  private def startPingPongActorPairs(messagesPerPair: Int, numPairs: Int, dispatcher: String)(implicit system: ActorSystem) = {
    val fullPathToDispatcher = "akka.actor." + dispatcher
    val latch = new CountDownLatch(numPairs * 2)
    val actors = for {
      i <- (1 to numPairs).toVector
    } yield {
      val ping = system.actorOf(PingPong.props(messagesPerPair, latch).withDispatcher(fullPathToDispatcher))
      val pong = system.actorOf(PingPong.props(messagesPerPair, latch).withDispatcher(fullPathToDispatcher))
      (ping, pong)
    }
    (actors, latch)
  }

  private def startUserServiceActors(numActors: Int, numQueriesPerActor: Int, numUsersInDBPerActor: Int, dispatcher: String, randomGen: Random)(implicit system: ActorSystem) = {
    val fullPathToDispatcher = "akka.actor." + dispatcher
    val latch = new CountDownLatch(numActors)
    val actors = for {
      i <- (1 to numActors).toVector
    } yield system.actorOf(UserServiceActor.props(numQueriesPerActor, numUsersInDBPerActor, latch, randomGen).withDispatcher(fullPathToDispatcher))
    (actors, latch)
  }

  private def initiatePingPongForPairs(refs: Vector[(ActorRef, ActorRef)], inFlight: Int) = {
    for {
      (ping, pong) <- refs
      _ <- 1 to inFlight
    } {
      ping.tell(Message, pong)
    }
  }

  private def printProgress(totalMessages: Long, numActors: Int, startNanoTime: Long) = {
    val durationMicros = (System.nanoTime() - startNanoTime) / 1000
    println(f"  $totalMessages messages by $numActors actors took ${durationMicros / 1000} ms, " +
      f"${totalMessages.toDouble / durationMicros}%,.2f M msg/s")
  }

  def requireRightNumberOfCores(numCores: Int) =
    require(
      Runtime.getRuntime.availableProcessors == numCores,
      s"Update the cores constant to ${Runtime.getRuntime.availableProcessors}"
    )

  def benchmarkPingPongActors(numMessagesPerActorPair: Int, numActors: Int, dispatcher: String, throughPut: Int, shutdownTimeout: Duration)(implicit system: ActorSystem): Unit = {
    val numPairs = numActors / 2
    val totalNumMessages = numPairs * numMessagesPerActorPair
    val (actors, latch) = startPingPongActorPairs(numMessagesPerActorPair, numPairs, dispatcher)
    val startNanoTime = System.nanoTime()
    initiatePingPongForPairs(actors, inFlight = throughPut * 2)
    latch.await(shutdownTimeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalNumMessages, numActors, startNanoTime)
  }

  def benchmarkUserServiceActor(numQueriesPerActor: Int, numUserServiceActors: Int, numUsersInDB: Int, randomSeed: Int, dispatcher: String, shutdownTimeout: Duration)(implicit system: ActorSystem): Unit = {
    val randomGen = new Random(randomSeed)
    val totalNumberOfQueries = numQueriesPerActor * numUserServiceActors
    val (actors, latch) = startUserServiceActors(numUserServiceActors, numUsersInDB, numQueriesPerActor, dispatcher, randomGen)
    val startNanoTime = System.nanoTime()
    for {
      serviceActor <- actors
      _ <- 0 until numQueriesPerActor
    } {
      serviceActor ! Request(randomGen.nextInt(numUsersInDB))
    }
    latch.await(shutdownTimeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalNumberOfQueries, numUserServiceActors, startNanoTime)
  }

  def tearDownSystem()(implicit system: ActorSystem): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, timeout)
  }

}

