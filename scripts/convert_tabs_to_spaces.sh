#!/bin/sh
echo "converting all tabs to 2 spaces"
find . -type f -name '*.html' -exec sed -i 's/[\t]/  /' {} \;

#find . -name "*.html" |while read line
#do
#  expand -i $line > $line.new
#  mv -f $line.new $line
#done

exit 0
