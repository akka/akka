/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.ants

import java.util.concurrent.TimeUnit
import scala.util.Random.{nextInt => randomInt}
import se.scalablesolutions.akka
import akka.actor.{ActorRef, Transactor, Scheduler}
import akka.actor.Actor.actorOf
import akka.stm.{Vector => _, _}
import akka.stm.Ref.Ref
import akka.stm.Transaction.Local._

object Config {
  val Dim = 80               // dimensions of square world
  val AntsSqrt = 7           // number of ants = AntsSqrt^2
  val FoodPlaces = 35        // number of places with food
  val FoodRange = 100        // range of amount of food at a place
  val PherScale = 10         // scale factor for pheromone drawing
  val AntMillis = 100        // how often an ant behaves (milliseconds)
  val EvapMillis = 1000      // how often pheromone evaporation occurs (milliseconds)
  val EvapRate = 0.99f       // pheromone evaporation rate
  val StartDelay = 1000      // delay before everything kicks off (milliseconds)
}

case class Ant(dir: Int, food: Boolean = false) {
  def turn(i: Int) = copy(dir = Util.dirBound(dir + i))
  def turnAround = turn(4)
  def pickUp = copy(food = true)
  def dropOff = copy(food = false)
}

case class Cell(food: Int = 0, pher: Float = 0, ant: Option[Ant] = None, home: Boolean = false) {
  def addFood(i: Int) = copy(food = food + i)
  def addPher(x: Float) = copy(pher = pher + x)
  def alterPher(f: Float => Float) = copy(pher = f(pher))
  def putAnt(antOpt: Option[Ant]) = copy(ant = antOpt)
  def makeHome = copy(home = true)
}

object EmptyCell extends Cell

class Place(initCell: Cell = EmptyCell) extends Ref(Some(initCell)) {
  def cell: Cell = get.get
  def food: Int = cell.food
  def food(i: Int) = alter(_.addFood(i))
  def hasFood = food > 0
  def pher: Float = cell.pher
  def pher(f: Float => Float) = alter(_.alterPher(f))
  def trail = alter(_.addPher(1))
  def ant: Option[Ant] = cell.ant
  def ant(f: Ant => Ant): Cell = alter(_.putAnt(ant map f))
  def enter(antOpt: Option[Ant]): Cell = alter(_.putAnt(antOpt))
  def enter(ant: Ant): Cell = enter(Some(ant))
  def leave = enter(None)
  def occupied: Boolean = ant.isDefined
  def makeHome = alter(_.makeHome)
  def home: Boolean = cell.home
}

object World {
  import Config._

  val homeOff = Dim / 4
  lazy val places = Vector.fill(Dim, Dim)(new Place)
  lazy val ants = setup
  lazy val evaporator = actorOf[Evaporator].start

  def place(loc: (Int, Int)) = places(loc._1)(loc._2)

  private def setup = atomic {
    for (i <- 1 to FoodPlaces) {
      place(randomInt(Dim), randomInt(Dim)) food (randomInt(FoodRange))
    }
    val homeRange = homeOff until (AntsSqrt + homeOff)
    for (x <- homeRange; y <- homeRange) yield {
      place(x, y).makeHome
      place(x, y) enter Ant(randomInt(8))
      actorOf(new AntActor(x, y)).start
    }
  }

  def start = {
    ants foreach (pingEvery(_, AntMillis))
    pingEvery(evaporator, EvapMillis)
  }

  private def pingEvery(actor: ActorRef, millis: Long) = 
    Scheduler.schedule(actor, "ping", Config.StartDelay, millis, TimeUnit.MILLISECONDS)
}

object Util {
  import Config._

  def bound(b: Int, n: Int) = {
    val x = n % b
    if (x < 0) x + b else x
  }

  def dirBound(n: Int) = bound(8, n)
  def dimBound(n: Int) = bound(Dim, n)

  val dirDelta = Map(0 -> (0, -1), 1 -> (1, -1), 2 -> (1, 0), 3 -> (1, 1),
                     4 -> (0, 1), 5 -> (-1, 1), 6 -> (-1, 0), 7 -> (-1, -1))
  def deltaLoc(x: Int, y: Int, dir: Int) = {
    val (dx, dy) = dirDelta(dirBound(dir))
    (dimBound(x + dx), dimBound(y + dy))
  }

  def rankBy[A, B: Ordering](xs: Seq[A], f: A => B) = Map(xs.sortBy(f).zip(Stream from 1): _*)

  def roulette(slices: Seq[Int]) = {
    val total = slices.sum
    val r = randomInt(total)
    var i, sum = 0
    while ((sum + slices(i)) <= r) {
      sum += slices(i)
      i += 1
    }
    i
  }
}

trait WorldActor extends Transactor {
  def act
  def receive = { case "ping" => act }
}

class AntActor(initLoc: (Int, Int)) extends WorldActor {
  import World._
  import Util._

  val locRef = Ref(initLoc)
  val homing = (p: Place) => p.pher + (100 * (if (p.home) 0 else 1))
  val foraging = (p: Place) => p.pher + p.food

  def loc = locRef.get.getOrElse(initLoc)
  def newLoc(l: (Int, Int)) = locRef swap l

  def act = {
    val (x, y) = loc
    val current = place(x, y)
    for (ant <- current.ant) {
      val ahead = place(deltaLoc(x, y, ant.dir))
      if (ant.food) { // homing
        if (current.home) dropFood
        else if (ahead.home && !ahead.occupied) move
        else random(homing)
      } else { // foraging
        if (!current.home && current.hasFood) pickUpFood
        else if (!ahead.home && ahead.hasFood && !ahead.occupied) move
        else random(foraging)
      }
    }
  }

  def move = {
    val (x, y) = loc
    val from = place(x, y)
    for (ant <- from.ant) {
      val toLoc = deltaLoc(x, y, ant.dir)
      val to = place(toLoc)
      to enter ant
      from.leave
      if (!from.home) from.trail
      newLoc(toLoc)
    }
  }

  def pickUpFood = {
    val current = place(loc)
    current food -1
    current ant (_.pickUp.turnAround)
  }

  def dropFood = {
    val current = place(loc)
    current food +1
    current ant (_.dropOff.turnAround)
  }

  def random[A: Ordering](ranking: Place => A) = {
    val (x, y) = loc
    val current = place(x, y)
    for (ant <- current.ant) {
      val delta = (turn: Int) => place(deltaLoc(x, y, ant.dir + turn))
      val ahead = delta(0)
      val aheadLeft = delta(-1)
      val aheadRight = delta(+1)
      val locations = Seq(ahead, aheadLeft, aheadRight)
      val ranks = rankBy(locations, ranking)
      val ranked = Seq(ranks(aheadLeft), (if (ahead.occupied) 0 else ranks(ahead)), ranks(aheadRight))
      val dir = roulette(ranked) - 1
      if (dir == 0) move
      else current ant (_.turn(dir))
    }
  }
}

class Evaporator extends WorldActor {
  import Config._
  import World._
  val evaporate = (pher: Float) => pher * EvapRate
  def act = for (x <- 0 until Dim; y <- 0 until Dim) place(x, y) pher evaporate
}
