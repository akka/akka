#!/bin/sh
find . -name *.scala -exec dos2unix {} \;
find . -name *.java -exec dos2unix {} \;
find . -name *.html -exec dos2unix {} \;
find . -name *.xml -exec dos2unix {} \;
find . -name *.conf -exec dos2unix {} \;
