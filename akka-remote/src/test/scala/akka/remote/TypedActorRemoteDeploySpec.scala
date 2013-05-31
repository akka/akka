/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit.AkkaSpec
import com.typesafe.config._
import scala.concurrent.{ Await, Future }
import TypedActorRemoteDeploySpec._
import akka.actor._
import scala.concurrent.duration._

object TypedActorRemoteDeploySpec {
  val conf = ConfigFactory.parseString("""
      akka.actor.provider = "akka.remote.RemoteActorRefProvider"
      akka.remote.netty.port = 0
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
  val remoteAddress =
    remoteSystem.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.address

  def verify[T](f: RemoteNameService ⇒ Future[T], expected: T) = {
    val ts = TypedActor(system)
    val echoService: RemoteNameService = ts.typedActorOf(
      TypedProps[RemoteNameServiceImpl].withDeploy(Deploy(scope = RemoteScope(remoteAddress))))
    Await.result(f(echoService), 3.seconds) must be(expected)
    val actor = ts.getActorRefFor(echoService)
    system.stop(actor)
  }

  "Typed actors" must {

    "be possible to deploy remotely and communicate with" in {
      verify({ _.getName }, remoteName)
    }

    "be possible to deploy remotely and be able to dereference self" in {
      verify({ _.getNameSelfDeref }, remoteName)
    }

  }

  override def atTermination() {
    remoteSystem.shutdown()
    remoteSystem.awaitTermination(5.seconds)
  }

}
