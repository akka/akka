#!/bin/sh
alias echo='echo $(date +"%F %T")'
now=$(date +"%s")
logfile="fortify-scans/sourceanalyzer-${now}.log"

if [ ! -d fortify-scans ]; then
  mkdir fortify-scans
else
  rm fortify-scans/*
fi

echo Starting analysis of scans
for D in ~/.fortify/sca21.1/build/*; do
  if [ -d "${D}" ]; then
    d="$(basename -- "$D")"
    echo Starting "$d" >> "$logfile"
    sourceanalyzer -b $d -f fortify-scans/"$d".fpr -scan
    echo Finished "$d" >> "$logfile"
    echo Finished "$d"
  fi
done
echo Scans complete

for F in fortify-scans/*.fpr; do
  f="$(basename -- "${F%.*}")"
  echo Generating report for "$f"
  BIRTReportGenerator -template "Developer Workbook" -source "$F" -format PDF -output "fortify-scans/$f.pdf"
done

echo Deleting NST files to clean up for next run
rm -rf ~/.fortify/sca21.1/build
echo Cleanup complete
