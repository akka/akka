package akka.camel

private[camel] object DangerousStuff {

  /**
   * Tries to execute body.
   *
   * Example below tries to start template and if it's unsuccessful it stops context in a safe way, by logging exceptions and then it rethrows exception thrown by template.start()
    <pre> try_(template.start()) otherwise context.stop() </pre>
   *
   * @param body block of code to execute.
   * @return Ok, if no exception is thrown by body.
   * @return Failed, if exception was thrown by body.

   */
  @inline def try_(body: => Unit): Result =
    try {
      body; Ok
    } catch {
      case e => Failed(e)
    }

  sealed trait Result {
    def otherwise(onError: => Unit): Unit
  }


  private[this] case object Ok extends Result {
    def otherwise(onError: => Unit) = () /* do nothing */
  }

  private[this] case class Failed(e: Throwable) extends Result {
    def otherwise(onError: => Unit) = {
      safe(onError)
      throw e
    }
  }

  /**
   * Executes the block and logs the exception, if it's thrown by the block, and swallows the exception.
   */
  @inline def safe(block: => Unit) {
    try {
      block
    } catch {
      //TODO replace with proper logging
      case e => println(e)
    }
  }

}