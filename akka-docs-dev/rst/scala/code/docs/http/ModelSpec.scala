package docs.http

//#import-model
import akka.http.scaladsl.model._

//#import-model

import akka.stream.testkit.AkkaSpec
import akka.util.ByteString
import akka.http.scaladsl.model.headers.{ GenericHttpCredentials, BasicHttpCredentials }
import org.scalatest.MustMatchers

class ModelSpec extends AkkaSpec {
  "construct request" in {
    //#construct-request
    import HttpMethods._

    // construct simple GET request to `homeUri`
    val homeUri = Uri("/abc")
    HttpRequest(GET, uri = homeUri)

    // construct simple GET request to "/index" which is converted to Uri automatically
    HttpRequest(GET, uri = "/index")

    // construct simple POST request containing entity
    val data = ByteString("abc")
    HttpRequest(POST, uri = "/receive", entity = data)

    // customize every detail of HTTP request
    import HttpProtocols._
    import MediaTypes._
    val userData = ByteString("abc")
    val authorization = headers.Authorization(BasicHttpCredentials("user", "pass"))
    HttpRequest(
      PUT,
      uri = "/user",
      entity = HttpEntity(`text/plain`, userData),
      headers = List(authorization),
      protocol = `HTTP/1.0`)
    //#construct-request
  }

  "construct response" in {
    //#construct-response
    import StatusCodes._

    // simple OK response without data created using the integer status code
    HttpResponse(200)

    // 404 response created using the named StatusCode constant
    HttpResponse(NotFound)

    // 404 response with a body explaining the error
    HttpResponse(404, entity = "Unfortunately, the resource couldn't be found.")

    // A redirecting response containing an extra header
    val locationHeader = headers.Location("http://example.com/other")
    HttpResponse(Found, headers = List(locationHeader))

    //#construct-response
  }

  "deal with headers" in {
    //#headers
    import akka.http.scaladsl.model.headers._

    // create a ``Location`` header
    val loc = Location("http://example.com/other")

    // create an ``Authorization`` header with HTTP Basic authentication data
    val auth = Authorization(BasicHttpCredentials("joe", "josepp"))

    // a method that extracts basic HTTP credentials from a request
    case class User(name: String, pass: String)
    def credentialsOfRequest(req: HttpRequest): Option[User] =
      for {
        Authorization(BasicHttpCredentials(user, pass)) <- req.header[headers.Authorization]
      } yield User(user, pass)
    //#headers

    credentialsOfRequest(HttpRequest(headers = List(auth))) should be(Some(User("joe", "josepp")))
    credentialsOfRequest(HttpRequest()) should be(None)
    credentialsOfRequest(HttpRequest(headers = List(Authorization(GenericHttpCredentials("Other", Map.empty[String, String]))))) should be(None)
  }
}
