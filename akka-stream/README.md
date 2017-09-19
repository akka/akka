Directory structure explained
=============================

Please note that the `src/main/java-jdk9-compile` are NOT for multi-release-jar support,
these are directories that are included in the build only if running on JDK9.
This is a MUST for releasing Akka including `j.u.c.Flow` support (numerous alternatives were explored).
