import akka._

Dependencies.http2

fork in run in Test := true
fork in Test := true

connectInput in run in Test := true
