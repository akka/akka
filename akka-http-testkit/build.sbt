import akka._

OSGi.httpTestkit
Dependencies.httpTestkit

scalacOptions in Compile  += "-language:postfixOps"

disablePlugins(MimaPlugin) // testkit, no bin compat guaranteed
