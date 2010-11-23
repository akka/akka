/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package sample.mist

import akka.actor._
import akka.actor.Actor._
import akka.http._
import javax.servlet.http.HttpServletResponse



/**
 * Define a top level service endpoint
 *
 *  @author Garrick Evans
 */
class InterestingService extends Actor with Endpoint {
  //
  // use the configurable dispatcher
  //
  self.dispatcher = Endpoint.Dispatcher

  final val ServiceRoot = "/interesting/"
  final val Multi = ServiceRoot + "multi/"

  //
  // The "multi" endpoint shows forking off multiple actions per request
  // It is triggered by POSTing to http://localhost:9998/interesting/multi/{foo}
  //  Try with/without a header named "Test-Token"
  //  Try with/without a form parameter named "Data"
  //
  def hookMultiActionA(uri: String): Boolean = uri startsWith Multi
  def provideMultiActionA(uri: String): ActorRef = actorOf(new ActionAActor(complete)).start

  def hookMultiActionB(uri: String): Boolean = uri startsWith Multi
  def provideMultiActionB(uri: String): ActorRef = actorOf(new ActionBActor(complete)).start

  //
  // this is where you want attach your endpoint hooks
  //
  override def preStart {
    //
    // we expect there to be one root and that it's already been started up
    // obviously there are plenty of other ways to obtaining this actor
    //  the point is that we need to attach something (for starters anyway)
    //  to the root
    //
    val root = ActorRegistry.actorsFor(classOf[RootEndpoint]).head
    root ! Endpoint.Attach(hookMultiActionA, provideMultiActionA)
    root ! Endpoint.Attach(hookMultiActionB, provideMultiActionB)
  }

    //
    // since this actor isn't doing anything else (i.e. not handling other messages)
    //  just assign the receive func like so...
    // otherwise you could do something like:
    //  def myrecv = {...}
    //  def receive = myrecv orElse handleHttpRequest
    //
  def receive = handleHttpRequest


  //
  // this guy completes requests after other actions have occured
  //
  lazy val complete = actorOf[ActionCompleteActor].start
}

class ActionAActor(complete: ActorRef) extends Actor {
  import javax.ws.rs.core.MediaType

  def receive = {
    //
    // handle a post request
    //
    case post:Post => {
      //
      // the expected content type of the request
      // similar to @Consumes
      //
      if (post.request.getContentType startsWith MediaType.APPLICATION_FORM_URLENCODED) {
        //
        // the content type of the response.
        // similar to @Produces annotation
        //
        post.response.setContentType(MediaType.TEXT_HTML)

        //
        // get the resource name
        //
        val name = post.request.getRequestURI.substring("/interesting/multi/".length)
        val response = if (name.length % 2 == 0)
          "<p>Action A verified request.</p>"
        else
          "<p>Action A could not verify request.</p>"

        post.response.getWriter.write(response)

        //
        // notify the next actor to coordinate the response
        //
        complete ! post
      }
      else {
        post.UnsupportedMediaType("Content-Type request header missing or incorrect (was '" + post.request.getContentType + "' should be '" + MediaType.APPLICATION_FORM_URLENCODED + "')")
      }
    }
  }
}

class ActionBActor(complete:ActorRef) extends Actor {
  import javax.ws.rs.core.MediaType

  def receive = {
    //
    // handle a post request
    //
    case post:Post => {
      //
      // the expected content type of the request
      // similar to @Consumes
      //
      if (post.request.getContentType startsWith MediaType.APPLICATION_FORM_URLENCODED) {
        //
        // pull some headers and form params
        //
        def default(any: Any): String = ""
        val token = post.getHeaderOrElse("Test-Token", default)
        val data = post.getParameterOrElse("Data", default)

        val (resp, status) = (token, data) match {
          case ("", _) => ("No token provided", HttpServletResponse.SC_FORBIDDEN)
          case (_, "") => ("No data", HttpServletResponse.SC_ACCEPTED)
          case _ => ("Data accepted", HttpServletResponse.SC_OK)
        }

        //
        // update the response body
        //
        post.response.getWriter.write(resp)

        //
        // notify the next actor to coordinate the response
        //
        complete ! (post, status)
      }
      else {
        post.UnsupportedMediaType("Content-Type request header missing or incorrect (was '" + post.request.getContentType + "' should be '" + MediaType.APPLICATION_FORM_URLENCODED + "')")
      }
    }

    case other: RequestMethod =>
      other.NotAllowed("Invalid method for this endpoint")
  }
}

class ActionCompleteActor extends Actor
{
  import collection.mutable.HashMap

  val requests = HashMap.empty[Int, Int]

  def receive = {
    case req: RequestMethod =>
      if (requests contains req.hashCode)
        complete(req)
      else
        requests += (req.hashCode -> 0)

    case t: Tuple2[RequestMethod, Int] =>
      if (requests contains t._1.hashCode)
        complete(t._1)
      else
        requests += (t._1.hashCode -> t._2)
  }

  def complete(req:RequestMethod) = requests.remove(req.hashCode) match {
    case Some(HttpServletResponse.SC_FORBIDDEN) => req.Forbidden("")
    case Some(HttpServletResponse.SC_ACCEPTED) => req.Accepted("")
    case Some(_) => req.OK("")
    case _ =>
  }
}