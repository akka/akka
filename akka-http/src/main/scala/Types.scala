/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package akka.http


/**
 * Structural type alias's required to work with both Servlet 3.0 and Jetty's Continuation API
 *
 * @author Garrick Evans
 */
object Types
{
  import javax.servlet. {ServletContext, ServletRequest, ServletResponse}

  type tAsyncRequest = {
    def startAsync:tAsyncContext
  }

  type tAsyncContext = {
    def complete:Unit
    def dispatch:Unit
    def dispatch(s:String):Unit
    def dispatch(c:ServletContext, s:String)
    def getRequest:ServletRequest
    def getResponse:ServletResponse
    def hasOriginalRequestAndResponse:Boolean
    def setTimeout(ms:Long):Unit
    def start(r:Runnable):Unit
  }

  type tContinuation = {
    def complete:Unit
    def isExpired:Boolean
    def isInitial:Boolean
    def isResumed:Boolean
    def isSuspended:Boolean
    def resume:Unit
    def suspend:Unit
    def undispatch:Unit
  }

  type tContinuationListener = {
    def onComplete(c:tContinuation)
    def onTimeout(c:tContinuation)
  }
}