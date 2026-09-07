"""Keep advisory assertion failures distinct from absent/incomplete harness coverage."""

import json
import sys

with open(sys.argv[1]) as report:
    data = json.load(report)
assert data["summary"]["total"] == 47, data["summary"]
assert {suite["name"]: suite["total"] for suite in data["suites"]} == {
    "capture": 30,
    "feature_flags": 17,
}, data["suites"]
for suite in data["suites"]:
    assert len(suite["tests"]) == suite["total"]
    assert len({test["name"] for test in suite["tests"]}) == suite["total"]
    for test in suite["tests"]:
        if not test["passed"]:
            print(f"{suite['name']}.{test['name']}: {test['message']}")
print(data["sdk_name"], data["summary"])
