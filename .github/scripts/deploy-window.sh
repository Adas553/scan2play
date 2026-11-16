#!/bin/bash
# Fails while parties are likely on, so Railway — which waits for every workflow of the commit — does not deploy `main` then
# (docs/context/10-2-migrations.md, "No deploy during a party"): Friday, Saturday and Sunday from 18:00 until 6:00 the next
# morning, Polish time. A commit whose message has [deploy-now] passes at any hour (an urgent fix).
#
# DAY (1 = Monday … 7) and HOUR (0 … 23) are the test's; without them, now in Warsaw.
set -eu

day=${DAY:-$(TZ=Europe/Warsaw date +%u)}
hour=${HOUR:-$(TZ=Europe/Warsaw date +%-H)}

if [[ "${COMMIT_MESSAGE:-}" == *"[deploy-now]"* ]]; then
    echo "[deploy-now] in the commit message: deployed at any hour."
    exit 0
fi

evening=false   # Friday, Saturday, Sunday from 18:00
morning=false   # the mornings after them, until 6:00
if (( day >= 5 && hour >= 18 )); then evening=true; fi
if (( (day == 6 || day == 7 || day == 1) && hour < 6 )); then morning=true; fi

if $evening || $morning; then
    echo "::error::Party hours (Fri–Sun 18:00–6:00, Polish time): main is not deployed now. Re-run this workflow after 6:00, or push a commit with [deploy-now] in its message for an urgent fix."
    exit 1
fi
echo "Outside the party hours: main may be deployed."
