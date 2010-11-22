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
  import javax.servlet. {ServletRequest, ServletResponse}

  type tAsyncRequest = {
    def startAsync: tAsyncRequestContext
  }

  /**
   * Used to match both AsyncContext and AsyncContinuation in order to complete the request
   */
  type tAsyncRequestContext = {
    def complete: Unit
    def getRequest: ServletRequest
    def getResponse: ServletResponse
  }
}