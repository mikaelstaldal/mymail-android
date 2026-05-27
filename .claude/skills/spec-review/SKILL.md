---
name: spec-review
description: Review the spec
disable-model-invocation: true
---

Start a subagent to review the specification in @spec/SPEC.md and highlight any inconsistencies and omissions that would be problematic when implementing.

Then go through the issues found by the subagent and resolve those which can be resolved without further decisions, 
use ../mymail/openapi.yaml as the API reference.

Finally, interview me about how to resolve the remaining issues, then resolve the issues.
