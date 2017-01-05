import akka._

Dependencies.http2

fork in run in Test := true
fork in Test := true

connectInput in run in Test := true

javaOptions in run in Test += "-javaagent:jetty-alpn-agent-2.0.5.jar"

javaOptions in test in Test += "-javaagent:jetty-alpn-agent-2.0.5.jar"
javaOptions in testOnly in Test += "-javaagent:jetty-alpn-agent-2.0.5.jar"
