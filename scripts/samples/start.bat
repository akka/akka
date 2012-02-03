@echo off
set SAMPLE=%~dp0..
set AKKA_HOME=%SAMPLE%\..\..\..\..
set JAVA_OPTS=-Xms1024M -Xmx1024M -Xss1M -XX:MaxPermSize=256M -XX:+UseParallelGC
set AKKA_CLASSPATH=%AKKA_HOME%\lib\scala-library.jar;%AKKA_HOME%\lib\akka\*
set SAMPLE_CLASSPATH=%SAMPLE%\config;%AKKA_CLASSPATH%;%SAMPLE%\lib\*

java %JAVA_OPTS% -cp "%SAMPLE_CLASSPATH%" -Dakka.home="%SAMPLE%" akka.kernel.Main
