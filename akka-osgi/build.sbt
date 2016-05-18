import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Dist }

AkkaBuild.defaultSettings
Dist.includeInDist := false
Formatting.formatSettings
OSGi.osgi
Dependencies.osgi

parallelExecution in Test := false
