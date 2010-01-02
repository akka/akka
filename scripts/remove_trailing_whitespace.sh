#!/bin/sh
echo "removing all trailing whitespace from all *.scala, *.html and *.xml files"
find . -type f -name '*.scala' -exec sed -i 's/[ \t]*$//' {} \;