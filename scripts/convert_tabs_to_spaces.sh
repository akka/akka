#!/bin/sh
find . -name "*.java" |while read line
do
  expand $line > $line.new
  mv -f $line.new $line
done
find . -name "*.scala" |while read line
do
  expand $line > $line.new
  mv -f $line.new $line
done
find . -name "*.html" |while read line
do
  expand $line > $line.new
  mv -f $line.new $line
done
find . -name "*.xml" |while read line
do
  expand $line > $line.new
  mv -f $line.new $line
done
echo "converted all tabs to 2 spaces"
exit 0
