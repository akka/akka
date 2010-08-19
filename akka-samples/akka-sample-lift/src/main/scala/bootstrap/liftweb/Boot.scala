package bootstrap.liftweb

import _root_.net.liftweb.util._
import _root_.net.liftweb.http._
import _root_.net.liftweb.sitemap._
import _root_.net.liftweb.sitemap.Loc._
import _root_.net.liftweb.http.auth._
import _root_.net.liftweb.common._
import Helpers._

import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging

import sample.lift._

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot extends Logging {
  def boot {
    // where to search snippet
    LiftRules.addToPackages("sample.lift")

    LiftRules.httpAuthProtectedResource.prepend {
      case (Req("liftcount" :: Nil, _, _)) => Full(AuthRole("admin"))
    }

    LiftRules.authentication = HttpBasicAuthentication("lift") {
      case ("someuser", "1234", req) => {
        log.info("You are now authenticated !")
        userRoles(AuthRole("admin"))
        true
      }
    }
    LiftRules.statelessDispatchTable.append(SimpleRestService)
    LiftRules.statelessDispatchTable.append(PersistentRestService)

    LiftRules.passNotFoundToChain = true

    val factory = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
        Supervise(
          actorOf[SimpleServiceActor],
          LifeCycle(Permanent)) ::
        Supervise(
          actorOf[PersistentServiceActor],
          LifeCycle(Permanent)) ::
        Nil))
    factory.newInstance.start

    // Build SiteMap
    // val entries = Menu(Loc("Home", List("index"), "Home")) :: Nil
    // LiftRules.setSiteMap(SiteMap(entries:_*))
  }
}

