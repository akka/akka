#!/bin/sh
alias echo='echo $(date +"%F %T")'
now=$(date +"%s")
logfile="fortify-scans/sourceanalyzer-${now}.log"
echo Starting scans
for D in ~/.fortify/sca21.1/build/*; do
    if [ -d "${D}" ]; then
        d="$(basename -- $D)"
        echo Starting $d >> $logfile
        sourceanalyzer -b $d -f fortify-scans/$d.fpr -scan
        echo Finished $d >> $logfile
        echo Finished $d
    fi
done
echo Scans complete
