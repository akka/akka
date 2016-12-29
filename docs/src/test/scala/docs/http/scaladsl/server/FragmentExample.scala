package docs.http.scaladsl.server

//#source-quote
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

object RouteFragment {
  val route: Route = pathEnd {
    get {
      complete("example")
    }
  }
}

object API {
  pathPrefix("version") {
    RouteFragment.route
  }
}
//#source-quote
