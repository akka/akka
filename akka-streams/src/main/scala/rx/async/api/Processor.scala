package rx.async.api

trait Processor[In, Out] extends Consumer[In] with Producer[Out]
