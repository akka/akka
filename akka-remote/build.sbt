import akka.{AkkaBuild, Dependencies, Formatting, OSGi}

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.remote
Dependencies.remote

parallelExecution in Test := false
