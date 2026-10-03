"""Writes the unit tests' coverage (JaCoCo's CSV report) as a table: the totals and the packages, least covered first.

Usage: coverage-summary.py target/site/jacoco/jacoco.csv  — prints Markdown; the workflow appends it to the job's summary.
A report, not a gate (review 6.4): it never fails the build.
"""
import csv
import sys
from collections import defaultdict


def percent(missed, covered):
    total = missed + covered
    return f"{100 * covered / total:.0f}%" if total else "—"


def main(path):
    packages = defaultdict(lambda: [0, 0, 0, 0])  # lines missed, covered; branches missed, covered
    with open(path, newline="", encoding="utf-8") as report:
        for row in csv.DictReader(report):
            counts = packages[row["PACKAGE"]]
            counts[0] += int(row["LINE_MISSED"])
            counts[1] += int(row["LINE_COVERED"])
            counts[2] += int(row["BRANCH_MISSED"])
            counts[3] += int(row["BRANCH_COVERED"])

    total = [sum(counts[i] for counts in packages.values()) for i in range(4)]
    print("## Unit test coverage (JaCoCo)\n")
    print(f"**Lines {percent(total[0], total[1])}**, branches {percent(total[2], total[3])} "
          "— the whole report is the artifact `coverage-report`.\n")
    print("| Package | Lines | Branches | Lines missed |")
    print("|---|---|---|---|")
    for name, counts in sorted(packages.items(), key=lambda item: item[1][1] / max(1, item[1][0] + item[1][1])):
        print(f"| `{name}` | {percent(counts[0], counts[1])} | {percent(counts[2], counts[3])} | {counts[0]} |")


if __name__ == "__main__":
    main(sys.argv[1])
