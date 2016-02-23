/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.testkit.AkkaSpec
import com.typesafe.config._
import scala.concurrent.{ Await, Future }
import TypedActorRemoteDeploySpec._
import akka.actor.{ Deploy, ActorSystem, TypedProps, TypedActor }
import scala.concurrent.duration._

object TypedActorRemoteDeploySpec {
  val conf = ConfigFactory.parseString("""
      akka.actor.provider = "akka.remote.RemoteActorRefProvider"
      akka.remote.netty.tcp.port = 0
                                                            """)

  trait RemoteNameService {
    def getName: Future[String]
    def getNameSelfDeref: Future[String]
  }

  class RemoteNameServiceImpl extends RemoteNameService {
    def getName: Future[String] = Future.successful(TypedActor.context.system.name)
    def getNameSelfDeref: Future[String] = TypedActor.self[RemoteNameService].getName
  }

}

class TypedActorRemoteDeploySpec extends AkkaSpec(conf) {
  val remoteName = "remote-sys"
  val remoteSystem = ActorSystem(remoteName, conf)
  val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

  def verify[T](f: RemoteNameService ⇒ Future[T], expected: T) = {
    val ts = TypedActor(system)
    val echoService: RemoteNameService = ts.typedActorOf(
      TypedProps[RemoteNameServiceImpl].withDeploy(Deploy(scope = RemoteScope(remoteAddress))))
    Await.result(f(echoService), 3.seconds) should ===(expected)
    val actor = ts.getActorRefFor(echoService)
    system.stop(actor)
    watch(actor)
    expectTerminated(actor)
  }

  "Typed actors" must {

    "be possible to deploy remotely and communicate with" in {
      verify({ _.getName }, remoteName)
    }

    "be possible to deploy remotely and be able to dereference self" in {
      verify({ _.getNameSelfDeref }, remoteName)
    }

  }

  override def afterTermination() {
    shutdown(remoteSystem)
  }

}
