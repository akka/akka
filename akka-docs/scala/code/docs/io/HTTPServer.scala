/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.io

import language.postfixOps

//#imports
import akka.actor._
import akka.util.{ ByteString, ByteStringBuilder }
import java.net.InetSocketAddress
//#imports

//#actor
class HttpServer(port: Int) extends Actor {

  val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)

  override def preStart {
    IOManager(context.system) listen new InetSocketAddress(port)
  }

  def receive = {

    case IO.NewClient(server) ⇒
      val socket = server.accept()
      state(socket) flatMap (_ ⇒ HttpServer.processRequest(socket))

    case IO.Read(socket, bytes) ⇒
      state(socket)(IO Chunk bytes)

    case IO.Closed(socket, cause) ⇒
      state(socket)(IO EOF None)
      state -= socket

  }

}
//#actor

//#actor-companion
object HttpServer {
  import HttpIteratees._

  def processRequest(socket: IO.SocketHandle): IO.Iteratee[Unit] =
    IO repeat {
      for {
        request ← readRequest
      } yield {
        val rsp = request match {
          case Request("GET", "ping" :: Nil, _, _, headers, _) ⇒
            OKResponse(ByteString("<p>pong</p>"),
              request.headers.exists { case Header(n, v) ⇒ n.toLowerCase == "connection" && v.toLowerCase == "keep-alive" })
          case req ⇒
            OKResponse(ByteString("<p>" + req.toString + "</p>"),
              request.headers.exists { case Header(n, v) ⇒ n.toLowerCase == "connection" && v.toLowerCase == "keep-alive" })
        }
        socket write OKResponse.bytes(rsp).compact
        if (!rsp.keepAlive) socket.close()
      }
    }

}
//#actor-companion

//#request-class
case class Request(meth: String, path: List[String], query: Option[String], httpver: String, headers: List[Header], body: Option[ByteString])
case class Header(name: String, value: String)
//#request-class

//#constants
object HttpConstants {
  val SP = ByteString(" ")
  val HT = ByteString("\t")
  val CRLF = ByteString("\r\n")
  val COLON = ByteString(":")
  val PERCENT = ByteString("%")
  val PATH = ByteString("/")
  val QUERY = ByteString("?")
}
//#constants

//#read-request
object HttpIteratees {
  import HttpConstants._

  def readRequest =
    for {
      requestLine ← readRequestLine
      (meth, (path, query), httpver) = requestLine
      headers ← readHeaders
      body ← readBody(headers)
    } yield Request(meth, path, query, httpver, headers, body)
  //#read-request

  //#read-request-line
  def ascii(bytes: ByteString): String = bytes.decodeString("US-ASCII").trim

  def readRequestLine =
    for {
      meth ← IO takeUntil SP
      uri ← readRequestURI
      _ ← IO takeUntil SP // ignore the rest
      httpver ← IO takeUntil CRLF
    } yield (ascii(meth), uri, ascii(httpver))
  //#read-request-line

  //#read-request-uri
  def readRequestURI = IO peek 1 flatMap {
    case PATH ⇒
      for {
        path ← readPath
        query ← readQuery
      } yield (path, query)
    case _ ⇒ sys.error("Not Implemented")
  }
  //#read-request-uri

  //#read-path
  def readPath = {
    def step(segments: List[String]): IO.Iteratee[List[String]] = IO peek 1 flatMap {
      case PATH ⇒ IO drop 1 flatMap (_ ⇒ readUriPart(pathchar) flatMap (segment ⇒ step(segment :: segments)))
      case _ ⇒ segments match {
        case "" :: rest ⇒ IO Done rest.reverse
        case _          ⇒ IO Done segments.reverse
      }
    }
    step(Nil)
  }
  //#read-path

  //#read-query
  def readQuery: IO.Iteratee[Option[String]] = IO peek 1 flatMap {
    case QUERY ⇒ IO drop 1 flatMap (_ ⇒ readUriPart(querychar) map (Some(_)))
    case _     ⇒ IO Done None
  }
  //#read-query

  //#read-uri-part
  val alpha = Set.empty ++ ('a' to 'z') ++ ('A' to 'Z') map (_.toByte)
  val digit = Set.empty ++ ('0' to '9') map (_.toByte)
  val hexdigit = digit ++ (Set.empty ++ ('a' to 'f') ++ ('A' to 'F') map (_.toByte))
  val subdelim = Set('!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=') map (_.toByte)
  val pathchar = alpha ++ digit ++ subdelim ++ (Set(':', '@') map (_.toByte))
  val querychar = pathchar ++ (Set('/', '?') map (_.toByte))

  def readUriPart(allowed: Set[Byte]): IO.Iteratee[String] = for {
    str ← IO takeWhile allowed map ascii
    pchar ← IO peek 1 map (_ == PERCENT)
    all ← if (pchar) readPChar flatMap (ch ⇒ readUriPart(allowed) map (str + ch + _)) else IO Done str
  } yield all

  def readPChar = IO take 3 map {
    case Seq('%', rest @ _*) if rest forall hexdigit ⇒
      java.lang.Integer.parseInt(rest map (_.toChar) mkString, 16).toChar
  }
  //#read-uri-part

  //#read-headers
  def readHeaders = {
    def step(found: List[Header]): IO.Iteratee[List[Header]] = {
      IO peek 2 flatMap {
        case CRLF ⇒ IO takeUntil CRLF flatMap (_ ⇒ IO Done found)
        case _    ⇒ readHeader flatMap (header ⇒ step(header :: found))
      }
    }
    step(Nil)
  }

  def readHeader =
    for {
      name ← IO takeUntil COLON
      value ← IO takeUntil CRLF flatMap readMultiLineValue
    } yield Header(ascii(name), ascii(value))

  def readMultiLineValue(initial: ByteString): IO.Iteratee[ByteString] = IO peek 1 flatMap {
    case SP ⇒ IO takeUntil CRLF flatMap (bytes ⇒ readMultiLineValue(initial ++ bytes))
    case _  ⇒ IO Done initial
  }
  //#read-headers

  //#read-body
  def readBody(headers: List[Header]) =
    if (headers.exists(header ⇒ header.name == "Content-Length" || header.name == "Transfer-Encoding"))
      IO.takeAll map (Some(_))
    else
      IO Done None
  //#read-body
}

//#ok-response
object OKResponse {
  import HttpConstants.CRLF

  val okStatus = ByteString("HTTP/1.1 200 OK")
  val contentType = ByteString("Content-Type: text/html; charset=utf-8")
  val cacheControl = ByteString("Cache-Control: no-cache")
  val date = ByteString("Date: ")
  val server = ByteString("Server: Akka")
  val contentLength = ByteString("Content-Length: ")
  val connection = ByteString("Connection: ")
  val keepAlive = ByteString("Keep-Alive")
  val close = ByteString("Close")

  def bytes(rsp: OKResponse) = {
    new ByteStringBuilder ++=
      okStatus ++= CRLF ++=
      contentType ++= CRLF ++=
      cacheControl ++= CRLF ++=
      date ++= ByteString(new java.util.Date().toString) ++= CRLF ++=
      server ++= CRLF ++=
      contentLength ++= ByteString(rsp.body.length.toString) ++= CRLF ++=
      connection ++= (if (rsp.keepAlive) keepAlive else close) ++= CRLF ++= CRLF ++= rsp.body result
  }

}
case class OKResponse(body: ByteString, keepAlive: Boolean)
//#ok-response

//#main
object Main extends App {
  val port = Option(System.getenv("PORT")) map (_.toInt) getOrElse 8080
  val system = ActorSystem()
  val server = system.actorOf(Props(new HttpServer(port)))
}
//#main
