"""Turns the failed tests of the JUnit XML reports into GitHub annotations (::error), one per failed test.

The logs of a run can be read only when signed in to GitHub; its annotations are public (the run page and the API
.../check-runs/<id>/annotations), so a failure can be diagnosed without a GitHub login. Usage: the report directories
(target/surefire-reports, target/failsafe-reports) as arguments; directories that do not exist are skipped.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_MESSAGE = 1500   # an annotation holds a few KB; the start of the message and of the trace is what matters


def escape(text):
    return text.replace('%', '%25').replace('\r', '').replace('\n', '%0A')


count = 0
for directory in sys.argv[1:]:
    for report in sorted(glob.glob(os.path.join(directory, 'TEST-*.xml'))):
        for case in ET.parse(report).getroot().iter('testcase'):
            for problem in list(case.findall('failure')) + list(case.findall('error')):
                text = (problem.get('message') or '') + '\n' + (problem.text or '')
                title = '%s.%s' % (case.get('classname', '?').rsplit('.', 1)[-1], case.get('name', '?'))
                print('::error title=%s::%s' % (escape(title), escape(text.strip()[:MAX_MESSAGE])))
                count += 1
if count == 0:
    print('::error title=No failed test in the reports::the build failed before or outside the tests (compilation, startup, '
          'a missing database): see the log')
