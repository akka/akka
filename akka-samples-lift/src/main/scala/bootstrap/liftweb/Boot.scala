package bootstrap.liftweb

import _root_.net.liftweb.util._
import _root_.net.liftweb.http._
import _root_.net.liftweb.sitemap._
import _root_.net.liftweb.sitemap.Loc._
//import _root_.net.liftweb.common._
import _root_.net.liftweb.http.auth._
import Helpers._

import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging

import sample.lift.{PersistentSimpleService, SimpleService}

/**
  * A class that's instantiated early and run.  It allows the application
  * to modify lift's environment
  */
class Boot {
  def boot {
    // where to search snippet
    LiftRules.addToPackages("sample.lift")
    
    LiftRules.httpAuthProtectedResource.prepend {
      case (ParsePath("liftcount" :: Nil, _, _, _)) => Full(AuthRole("admin"))
    }

    LiftRules.authentication = HttpBasicAuthentication("lift") {
      case ("someuser", "1234", req) => {
        Log.info("You are now authenticated !")
        userRoles(AuthRole("admin"))
        true
      }
    }
    
    LiftRules.passNotFoundToChain = true
    
    object factory extends SupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(OneForOne, 3, 100),
          Supervise(
            new SimpleService,      
            LifeCycle(Permanent, 100)) ::
          Supervise(
            new PersistentSimpleService,
            LifeCycle(Permanent, 100)) ::
          Nil)
      }
    }
    val supervisor = factory.newSupervisor
    supervisor.startSupervisor
    
    // Build SiteMap
    // val entries = Menu(Loc("Home", List("index"), "Home")) :: Nil
    // LiftRules.setSiteMap(SiteMap(entries:_*))
  }
}

