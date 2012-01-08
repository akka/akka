package akka.util

object ErrorUtils{
  /**
   * Executes a block and returns the result wrapped by Right class, or exception wrapped by Left class.
   */
  def either[T](block:() => T) : Either[Throwable,T] = try {Right(block())} catch {case e => Left(e)}

  /**
   * Executes all blocks in order and collects exceptions. It guarantees to execute all blocks, even if some of them fail.
   * It throws a BlockException, if any number of blocks fail. BlockException contains a list of thrown exceptions.
   *
   * <br/><br/>Example:
       <pre>
           tryAll(
             service1.stop,
             service2.shutdown,
             service3.kill
           )
       </pre>
   *
   * @return nothing
   * @throws BlockException if any number of blocks fail. BlockException contains a list of thrown exceptions.
   *
   */
  def tryAll(block1 : => Unit, block2 : => Unit = {}, block3 : => Unit= {}, block4 : => Unit = {}, block5 : => Unit = {}, block6 : => Unit = {}) = {
    val blocks = List(()=>block1, ()=>block2, ()=>block3, ()=>block4, ()=>block5, ()=>block6)
    val errors = blocks.toList.map(either(_)).filter(_.isLeft).map{case Left(e) => e}
    if (!errors.isEmpty) throw new BlockException(errors)
  }

  case class BlockException(errors : List[Throwable]) extends RuntimeException("There were errors while executing blocks: "+errors)
}
