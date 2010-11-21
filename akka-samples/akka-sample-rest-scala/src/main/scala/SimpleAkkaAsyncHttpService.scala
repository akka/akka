/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package sample.rest.scala

import akka.actor._
import akka.actor.Actor._
import akka.http._



/**
 * Define a top level service endpoint
 *  Usage: GET or POST to http://localhost:9998/simple/same or http://localhost:9998/simple/new
 *
 *  @author Garrick Evans
 */
class SimpleAkkaAsyncHttpService extends Actor with Endpoint
{
  final val ServiceRoot = "/simple/"
  final val ProvideSameActor = ServiceRoot + "same"
  final val ProvideNewActor = ServiceRoot + "new"

    //
    // use the configurable dispatcher
    //
  self.dispatcher = Endpoint.Dispatcher

    //
    // there are different ways of doing this - in this case, we'll use a single hook function
    //  and discriminate in the provider; alternatively we can pair hooks & providers
    //
  def hook(uri: String): Boolean = ((uri == ProvideSameActor) || (uri == ProvideNewActor))
  def provide(uri: String): ActorRef =
  {
    if (uri == ProvideSameActor)
      same
    else
      actorOf[BoringActor].start
  }

    //
    // this is where you want attach your endpoint hooks
    //
  override def preStart =
    {
        //
        // we expect there to be one root and that it's already been started up
        // obviously there are plenty of other ways to obtaining this actor
        //  the point is that we need to attach something (for starters anyway)
        //  to the root
        //
      val root = ActorRegistry.actorsFor(classOf[RootEndpoint]).head
      root ! Endpoint.Attach(hook, provide)
    }

    //
    // since this actor isn't doing anything else (i.e. not handling other messages)
    //  just assign the receive func like so...
    // otherwise you could do something like:
    //  def myrecv = {...}
    //  def receive = myrecv orElse _recv
    //
  def receive = _recv


  //
  // this will be our "same" actor provided with ProvideSameActor endpoint is hit
  //
  lazy val same = actorOf[BoringActor].start
}

/**
 * Define a service handler to respond to some HTTP requests
 */
class BoringActor extends Actor
{
  import java.util.Date
  import javax.ws.rs.core.MediaType

  var gets = 0
  var posts = 0
  var lastget:Option[Date] = None
  var lastpost:Option[Date] = None

  def receive =
  {
      //
      // handle a get request
      //
    case get:Get =>
    {
        //
        // the content type of the response.
        // similar to @Produces annotation
        //
      get.response.setContentType(MediaType.TEXT_HTML)

      get.timeout(5000)

      //
      // "work"
      //
      gets += 1
      lastget = Some(new Date)

      //
      // respond
      //
      val res = "<p>Gets: "+gets+" Posts: "+posts+"</p><p>Last Get: "+lastget.getOrElse("Never").toString+" Last Post: "+lastpost.getOrElse("Never").toString+"</p>"
      get.OK(res)
    }

      //
      // handle a post request
      //
    case post:Post =>
    {
        //
        // the expected content type of the request
        // similar to @Consumes
        //
      if (post.request.getContentType startsWith MediaType.APPLICATION_FORM_URLENCODED)
      {
          //
          // the content type of the response.
          // similar to @Produces annotation
          //
        post.response.setContentType(MediaType.TEXT_HTML)

        //
        // "work"
        //
        posts += 1
        lastpost = Some(new Date)

        //
        // respond
        //
        val res = "<p>Gets: "+gets+" Posts: "+posts+"</p><p>Last Get: "+lastget.getOrElse("Never").toString+" Last Post: "+lastpost.getOrElse("Never").toString+"</p>"
        post.OK(res)
      }
      else
      {
        post.UnsupportedMediaType("Content-Type request header missing or incorrect (was '" + post.request.getContentType + "' should be '" + MediaType.APPLICATION_FORM_URLENCODED + "')")
      }
    }

    case other if other.isInstanceOf[RequestMethod] =>
    {
      other.asInstanceOf[RequestMethod].NotAllowed("Invalid method for this endpoint")
    }
  }
}
