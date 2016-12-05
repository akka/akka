/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.scaladsl.model.HttpRequest
import akka.util.ByteString
import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.ActorSystem
import akka.stream._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.StdIn

object TestSingleRequest extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val url = StdIn.readLine("url? ")

  val x = Http().singleRequest(HttpRequest(uri = url))

  val res = Await.result(x, 10.seconds)

  val response = res.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)

  println(" ------------ RESPONSE ------------")
  println(Await.result(response, 10.seconds))
  println(" -------- END OF RESPONSE ---------")

  system.terminate()
}
