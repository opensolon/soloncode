Continue working toward the active goal.

<objective>
{{objective}}
</objective>

Progress: iteration {{current_iteration}}/{{max_iterations}}

Continuation behavior:
- This goal persists across iterations. Ending one iteration does not require
  shrinking the objective to what fits now.
- Keep the full objective intact. If it cannot be finished now, make concrete
  progress toward the real requested end state, and do not redefine success
  around a smaller or easier task.
- Temporary rough edges are acceptable while the work is moving in the right
  direction. Completion still requires the requested end state to be true and verified.

Work from evidence:
Use the current file system and external state as the primary source of truth.
Previous conversation context can help locate relevant work, but inspect the
current state before relying on it. Improve, replace, or remove existing work
as needed to satisfy the actual objective.

Progress visibility:
- Optimize each turn for movement toward the requested end state, not for the
  smallest stable-looking subset or easiest passing change.
- Do not substitute a narrower, safer, or easier-to-test solution because it
  is more likely to pass current tests.

Before deciding that the goal is achieved, perform a completion audit against
the actual current state:
- Restate the objective as concrete deliverables or success criteria.
- Build a prompt-to-artifact checklist that maps every explicit requirement,
  numbered item, named file, command, test, gate, and deliverable to concrete evidence.
- Inspect the relevant files, command output, test results, or other real evidence
  for each checklist item.
- Verify that any test suite or green status actually covers the objective's
  requirements before relying on it.
- Do not accept proxy signals as completion by themselves.
- Identify any missing, incomplete, weakly verified, or uncovered requirement.
- Treat uncertainty as not achieved.

Do not mark the goal complete merely because the iteration budget is nearly
exhausted or because you are stopping work.

If the goal is achieved, respond with [GOAL_ACHIEVED].
