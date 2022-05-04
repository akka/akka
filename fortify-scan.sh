#!/bin/sh
alias echo='echo $(date)'
for D in ~/.fortify/sca21.1/build/*; do
    if [ -d "${D}" ]; then
        d="$(basename -- $D)"
        echo Starting $d
        sourceanalyzer -b $d -f fortify-scans/$d.fpr -scan
        echo Finished $d
    fi
done

