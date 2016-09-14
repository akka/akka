import akka._

Dependencies.http2

fork in run in Test := true
connectInput in run in Test := true

javaOptions in run in Test += "-javaagent:jetty-alpn-agent-2.0.4.jar"