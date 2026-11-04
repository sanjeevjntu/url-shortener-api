"""Tests for the orchestration control plane. Run: python3 -m unittest discover -s orchestration -v"""

from __future__ import annotations

import contextlib
import io
import json
import os
import re
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import engine  # noqa: E402
import run as cli  # noqa: E402
from engine import (  # noqa: E402
    AWAITING,
    BLOCKED,
    CACHED,
    DEGRADED,
    FAILED,
    PASSED,
    SKIPPED,
    LockTable,
    Orchestrator,
    Pipeline,
    PipelineError,
    check_gate,
    compute_metrics,
    read_events,
    read_signoff,
    verify_ledger,
)

PROJECT = HERE.parent
POLICY = {"max_retries": 2, "retry_backoff_seconds": 0, "max_workers": 3}

# -- tiny gate programs (run with the same Python, so the tests need nothing else) ---------------------------------------

OK = [sys.executable, "-c", "pass"]
FAIL = [sys.executable, "-c", "import sys; sys.exit(1)"]
FAIL_UNTIL = (
    "import pathlib,sys\n"
    "p=pathlib.Path(sys.argv[1]); n=(int(p.read_text()) if p.exists() else 0)+1; p.write_text(str(n))\n"
    "sys.exit(0 if n>int(sys.argv[2]) else 1)"
)
COUNT = (
    "import pathlib,sys\n"
    "p=pathlib.Path(sys.argv[1]); p.write_text(str((int(p.read_text()) if p.exists() else 0)+1))"
)
BARRIER = (
    "import pathlib,sys,time\n"
    "me,other=pathlib.Path(sys.argv[1]),pathlib.Path(sys.argv[2]); me.write_text('x')\n"
    "for _ in range(100):\n"
    "    if other.exists(): sys.exit(0)\n"
    "    time.sleep(0.05)\n"
    "sys.exit(1)"
)
SLOW_LOG = (
    "import pathlib,sys,time\n"
    "log=pathlib.Path(sys.argv[1])\n"
    "open(log,'a').write('start\\n'); time.sleep(0.25); open(log,'a').write('end\\n')"
)
ENV_IS = "import os,sys; sys.exit(0 if os.environ.get(sys.argv[1])==sys.argv[2] else 1)"


def cmd(argv, **extra):
    return {"type": "command", "name": extra.pop("name", "gate"), "argv": argv, **extra}


def py(code, *args):
    return [sys.executable, "-c", code, *map(str, args)]


def stage(sid, deps=(), gates=None, **extra):
    base = {"id": sid, "depends_on": list(deps), "autonomy": "agent", "exit_gates": gates if gates is not None else [cmd(OK)]}
    base.update(extra)
    return base


def pipeline(*stages, policy=None):
    return Pipeline.from_dict({"name": "t", "policy": {**POLICY, **(policy or {})}, "stages": list(stages)})


class Sandbox(unittest.TestCase):
    """A throwaway project folder with a sign-off table."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="orch-test-"))
        (self.root / "docs").mkdir()
        self.set_signoff({})
        self.addCleanup(shutil.rmtree, self.root, True)

    def set_signoff(self, rows):
        lines = ["| Item | Reviewer | Date | Decision | Notes |", "|---|---|---|---|---|"]
        for item, (reviewer, date, decision) in rows.items():
            lines.append(f"| {item} | {reviewer} | {date} | {decision} | |")
        (self.root / "docs" / "SIGNOFF.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def orch(self, pipe, **kw):
        kw.setdefault("sleep", lambda s: None)
        kw.setdefault("log", lambda s: None)
        kw.setdefault("env", dict(os.environ))
        return Orchestrator(self.root, pipe, **kw)

    def events(self, kind=None):
        evs = read_events(self.root / "orchestration" / "ledger")
        return [e for e in evs if kind is None or e["event"] == kind]

    def write(self, rel, text="x"):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path


# ----------------------------------------------------------------------------------------------------------------------
# Graph
# ----------------------------------------------------------------------------------------------------------------------


class GraphTests(unittest.TestCase):
    def test_cycle_is_rejected(self):
        with self.assertRaisesRegex(PipelineError, "cycle"):
            pipeline(stage("a", ["b"]), stage("b", ["a"]))

    def test_unknown_dependency_and_self_dependency(self):
        with self.assertRaisesRegex(PipelineError, "unknown stage"):
            pipeline(stage("a", ["ghost"]))
        with self.assertRaisesRegex(PipelineError, "itself"):
            pipeline(stage("a", ["a"]))

    def test_duplicate_ids(self):
        with self.assertRaisesRegex(PipelineError, "duplicate"):
            pipeline(stage("a"), stage("a"))

    def test_approvals_and_autonomy_must_agree(self):
        with self.assertRaisesRegex(PipelineError, "agent\\+human"):
            pipeline(stage("a", approvals=["S1"]))  # approvals on an autonomous stage
        with self.assertRaisesRegex(PipelineError, "needs approvals"):
            pipeline(stage("a", autonomy="agent+human"))  # human stage with nothing to approve
        pipeline(stage("a", autonomy="agent+human", release_gate=True))  # a release gate is allowed

    def test_gate_definitions_are_checked(self):
        with self.assertRaisesRegex(PipelineError, "unknown gate type"):
            pipeline(stage("a", gates=[{"type": "magic"}]))
        with self.assertRaisesRegex(PipelineError, "missing 'path'"):
            pipeline(stage("a", gates=[{"type": "file_exists"}]))
        with self.assertRaisesRegex(PipelineError, "max_retries"):
            pipeline(stage("a"), policy={"max_retries": 99})

    def test_waves_group_independent_stages(self):
        p = pipeline(stage("a"), stage("b", ["a"]), stage("c", ["a"]), stage("d", ["b", "c"]))
        self.assertEqual(p.waves(), [["a"], ["b", "c"], ["d"]])
        self.assertEqual(p.ancestors("d"), {"a", "b", "c"})
        self.assertEqual(p.descendants("a"), {"b", "c", "d"})

    def test_required_approvals_are_transitive(self):
        p = pipeline(
            stage("a", autonomy="agent+human", approvals=["S1"]),
            stage("b", ["a"], autonomy="agent+human", approvals=["S2"]),
            stage("r", ["b"], autonomy="agent+human", release_gate=True),
        )
        self.assertEqual(p.required_approvals("r"), ["S1", "S2"])


# ----------------------------------------------------------------------------------------------------------------------
# Gates
# ----------------------------------------------------------------------------------------------------------------------


class GateTests(Sandbox):
    def gate(self, g):
        return check_gate(g, self.root, dict(os.environ), LockTable())

    def test_file_hash_detects_change(self):
        f = self.write("db/V1.sql", "create table t();")
        digest = engine.file_sha256(f)
        self.assertEqual(self.gate({"type": "file_hash", "path": "db/V1.sql", "sha256": digest})[0], "pass")
        f.write_text("create table t(x int);")
        status, detail = self.gate({"type": "file_hash", "path": "db/V1.sql", "sha256": digest})
        self.assertEqual(status, "fail")
        self.assertIn("changed", detail)

    def test_file_contains_counts_matches(self):
        self.write("d.md", "## D1.\n## D2.\n")
        g = {"type": "file_contains", "path": "d.md", "pattern": r"^## D\d+\.", "min_count": 2}
        self.assertEqual(self.gate(g)[0], "pass")
        self.assertEqual(self.gate({**g, "min_count": 3})[0], "fail")

    def test_forbidden_text(self):
        self.write("src/A.java", 'String key = "dev-secret";')
        self.write("src/B.java", "clean")
        g = {"type": "forbidden_text", "include": ["src/**"], "patterns": ["dev-secret"]}
        status, detail = self.gate(g)
        self.assertEqual(status, "fail")
        self.assertIn("src/A.java:1", detail)
        (self.root / "src" / "A.java").write_text("clean")
        self.assertEqual(self.gate(g)[0], "pass")

    def test_md_links(self):
        self.write("docs/A.md", "# A\n\n## 1. Problem and assumptions\n")
        self.write(
            "README.md",
            "[ok](docs/A.md#1-problem-and-assumptions) [self](#intro)\n\n## Intro\n\n"
            "```\n[ignored](nowhere.md)\n```\nand `[also ignored](nope.md)` [web](https://example.com)\n",
        )
        g = {"type": "md_links", "include": ["README.md"]}
        self.assertEqual(self.gate(g)[0], "pass")
        self.write("README.md", "[gone](docs/Missing.md)")
        self.assertIn("missing target", self.gate(g)[1])
        self.write("README.md", "[bad](docs/A.md#no-such-heading)")
        self.assertIn("no heading", self.gate(g)[1])

    def test_missing_tool_and_env_are_blocked_not_failed(self):
        self.assertEqual(self.gate({"type": "tool_available", "tool": "no-such-tool-xyz"})[0], "blocked")
        self.assertEqual(self.gate({"type": "env_present", "var": "NOPE_NOT_SET"})[0], "blocked")
        self.assertEqual(self.gate(cmd(["no-such-binary-xyz"]))[0], "blocked")

    def test_command_timeout_fails(self):
        status, detail = self.gate(cmd([sys.executable, "-c", "import time; time.sleep(5)"], timeout=1))
        self.assertEqual(status, "fail")
        self.assertIn("timed out", detail)

    def test_command_expands_environment_variables(self):
        env = {**os.environ, "WANT": "yes"}
        gate = cmd(py(ENV_IS, "WANT", "${WANT}"))
        self.assertEqual(check_gate(gate, self.root, env, LockTable())[0], "pass")


# ----------------------------------------------------------------------------------------------------------------------
# Execution: ordering, parallelism, synchronisation
# ----------------------------------------------------------------------------------------------------------------------


class ExecutionTests(Sandbox):
    def test_dependency_order_and_status(self):
        p = pipeline(stage("a"), stage("b", ["a"]), stage("c", ["b"]))
        result = self.orch(p, workers=1).run()
        self.assertEqual(result.status, "SUCCESS")
        self.assertEqual(result.exit_code, 0)
        self.assertEqual([e["stage"] for e in self.events("stage_started")], ["a", "b", "c"])
        self.assertEqual(set(result.stages.values()), {PASSED})

    def test_independent_stages_really_overlap(self):
        x, y = self.root / "x.flag", self.root / "y.flag"
        p = pipeline(
            stage("x", gates=[cmd(py(BARRIER, x, y))]),
            stage("y", gates=[cmd(py(BARRIER, y, x))]),
        )
        # Each gate waits for the other's flag: only possible if both stages run at the same time.
        self.assertEqual(self.orch(p, workers=2).run().status, "SUCCESS")

    def test_workers_limit_is_respected(self):
        x, y = self.root / "x.flag", self.root / "y.flag"
        p = pipeline(stage("x", gates=[cmd(py(BARRIER, x, y), timeout=30)]), stage("y", gates=[cmd(py(BARRIER, y, x), timeout=30)]))
        self.assertEqual(self.orch(p, workers=1).run().status, "FAILED")  # serial: the barrier can never be met

    def test_exclusive_gates_are_serialised(self):
        log = self.root / "log.txt"
        slow = cmd(py(SLOW_LOG, log), exclusive="gradle")
        p = pipeline(stage("x", gates=[slow]), stage("y", gates=[slow]))
        self.assertEqual(self.orch(p, workers=2).run().status, "SUCCESS")
        self.assertEqual(log.read_text().split(), ["start", "end", "start", "end"])

    def test_join_waits_for_all_parents(self):
        p = pipeline(stage("a"), stage("b"), stage("j", ["a", "b"]))
        self.orch(p, workers=2).run()
        started = [e["stage"] for e in self.events("stage_started")]
        self.assertEqual(started[-1], "j")


# ----------------------------------------------------------------------------------------------------------------------
# Retries, fallback, safe-stop, rollback, entry gates, policy
# ----------------------------------------------------------------------------------------------------------------------


class ResilienceTests(Sandbox):
    def test_transient_gate_is_retried_then_passes(self):
        counter = self.root / "n"
        gate = cmd(py(FAIL_UNTIL, counter, 2), transient=True, retries=2)
        result = self.orch(pipeline(stage("a", gates=[gate]))).run()
        self.assertEqual(result.stages["a"], PASSED)
        self.assertEqual(len(self.events("gate_retry")), 2)
        self.assertEqual(self.events("gate_result")[-1]["attempts"], 3)

    def test_retries_are_capped_by_policy(self):
        counter = self.root / "n"
        gate = cmd(py(FAIL_UNTIL, counter, 99), transient=True, retries=9)  # asks for 9, policy allows 2
        result = self.orch(pipeline(stage("a", gates=[gate]))).run()
        self.assertEqual(result.stages["a"], FAILED)
        self.assertEqual(counter.read_text(), "3")  # 1 attempt + exactly 2 retries

    def test_non_transient_gate_is_never_retried(self):
        counter = self.root / "n"
        gate = cmd(py(FAIL_UNTIL, counter, 99), retries=5)  # no transient flag
        self.orch(pipeline(stage("a", gates=[gate]))).run()
        self.assertEqual(counter.read_text(), "1")

    def test_fallback_marks_stage_degraded_and_lets_downstream_continue(self):
        fb = {
            "description": "ship in sync mode",
            "release_overrides": {"MODE": "sync"},
            "exit_gates": [cmd(py(ENV_IS, "MODE", "sync"))],
        }
        p = pipeline(stage("a", gates=[cmd(FAIL)], fallback=fb), stage("b", ["a"]))
        result = self.orch(p).run()
        self.assertEqual(result.stages, {"a": DEGRADED, "b": PASSED})
        self.assertEqual(self.events("fallback")[0]["overrides"], {"MODE": "sync"})

    def test_a_degraded_stage_is_retried_not_cached_as_a_clean_pass(self):
        flag = self.root / "primary.ok"
        primary = cmd(py("import pathlib,sys; sys.exit(0 if pathlib.Path(sys.argv[1]).exists() else 1)", flag))
        fb = {"description": "ship in sync mode", "exit_gates": [cmd(OK)]}
        p = pipeline(stage("a", gates=[primary], fallback=fb))
        self.assertEqual(self.orch(p).run().stages["a"], DEGRADED)
        self.assertEqual(self.orch(p).run().stages["a"], DEGRADED)  # primary gates are tried again, not served from cache
        flag.write_text("fixed")
        self.assertEqual(self.orch(p).run().stages["a"], PASSED)
        self.assertEqual(self.orch(p).run().stages["a"], CACHED)  # only now is it reusable

    def test_failure_without_fallback_safe_stops(self):
        p = pipeline(stage("a", gates=[cmd(FAIL)]), stage("b"), stage("c", ["a"]))
        result = self.orch(p, workers=1).run()
        self.assertEqual(result.stages, {"a": FAILED, "b": SKIPPED, "c": SKIPPED})
        self.assertEqual(result.status, "FAILED")
        self.assertEqual(result.exit_code, 1)
        self.assertEqual(self.events("safe_stop")[0]["reason"], "stage a failed")

    def test_keep_going_runs_independent_branches(self):
        p = pipeline(stage("a", gates=[cmd(FAIL)]), stage("b"), stage("c", ["a"]))
        result = self.orch(p, workers=1, keep_going=True).run()
        self.assertEqual(result.stages, {"a": FAILED, "b": PASSED, "c": SKIPPED})

    def test_stop_file_halts_the_run(self):
        self.write("orchestration/STOP", "operator says stop")
        result = self.orch(pipeline(stage("a"), stage("b", ["a"]))).run()
        self.assertEqual(result.status, "ABORTED")
        self.assertEqual(set(result.stages.values()), {SKIPPED})
        self.assertEqual(self.events("stage_started"), [])

    def test_entry_gate_blocks_without_attempting_the_stage(self):
        entry = [{"type": "tool_available", "name": "needs docker", "tool": "no-such-tool-xyz"}]
        counter = self.root / "n"
        p = pipeline(stage("a", gates=[cmd(py(COUNT, counter))], entry_gates=entry), stage("b", ["a"]))
        result = self.orch(p).run()
        self.assertEqual(result.stages, {"a": BLOCKED, "b": SKIPPED})
        self.assertEqual(result.status, "BLOCKED")
        self.assertFalse(counter.exists())  # the exit gate never ran

    def test_missing_command_in_exit_gate_is_blocked_not_failed(self):
        result = self.orch(pipeline(stage("a", gates=[cmd(["no-such-binary-xyz"])]))).run()
        self.assertEqual(result.stages["a"], BLOCKED)

    def test_rollback_is_recommended_but_not_executed_by_default(self):
        marker = self.root / "rolled-back"
        rb = {"description": "undo", "argv": py("import pathlib,sys; pathlib.Path(sys.argv[1]).write_text('x')", marker)}
        p = pipeline(stage("a", gates=[cmd(FAIL)], rollback=rb))
        self.orch(p).run()
        rec = self.events("rollback_recommended")[0]
        self.assertFalse(rec["will_execute"])
        self.assertFalse(marker.exists())
        self.assertEqual(self.events("rollback_executed"), [])

    def test_rollback_executes_only_with_the_flag(self):
        marker = self.root / "rolled-back"
        rb = {"description": "undo", "argv": py("import pathlib,sys; pathlib.Path(sys.argv[1]).write_text('x')", marker)}
        p = pipeline(stage("a", gates=[cmd(FAIL)], rollback=rb))
        self.orch(p, rollback=True).run()
        self.assertTrue(marker.exists())
        self.assertEqual(self.events("rollback_executed")[0]["returncode"], 0)

    def test_policy_violation_aborts_before_any_stage(self):
        self.write("db/V1.sql", "original")
        digest = engine.file_sha256(self.root / "db" / "V1.sql")
        policy = {"preflight": [{"type": "file_hash", "name": "V1 immutable", "path": "db/V1.sql", "sha256": digest}]}
        p = pipeline(stage("a"), policy=policy)
        self.assertEqual(self.orch(p).run().status, "SUCCESS")
        (self.root / "db" / "V1.sql").write_text("edited after release")
        result = self.orch(p).run()
        self.assertEqual(result.status, "ABORTED")
        self.assertEqual(result.exit_code, 3)
        self.assertEqual(len(self.events("stage_started")), 1)  # only the first (clean) run started a stage


# ----------------------------------------------------------------------------------------------------------------------
# Human approvals
# ----------------------------------------------------------------------------------------------------------------------


def approval_pipeline():
    return pipeline(
        stage("schema", autonomy="agent+human", approvals=["S1"]),
        stage("api", ["schema"]),
        stage("release", ["api"], autonomy="agent+human", release_gate=True),
    )


class ApprovalTests(Sandbox):
    def test_signoff_parsing(self):
        self.set_signoff(
            {
                "S1": ("Sanjeev", "2026-11-05", "Approved"),
                "S2": ("", "", ""),
                "S3": ("Sanjeev", "2026-11-05", "Rejected: too loose"),
                "S4": ("Sanjeev", "", "Approved"),  # no date: not valid
            }
        )
        got = {k: v[0] for k, v in read_signoff(self.root / "docs" / "SIGNOFF.md").items()}
        self.assertEqual(got, {"S1": "granted", "S2": "pending", "S3": "rejected", "S4": "pending"})

    def test_pending_approval_lets_work_continue_but_blocks_release(self):
        self.set_signoff({"S1": ("", "", "")})
        result = self.orch(approval_pipeline()).run()
        self.assertEqual(result.stages, {"schema": AWAITING, "api": PASSED, "release": BLOCKED})
        self.assertEqual(result.status, "BLOCKED")
        self.assertEqual(result.exit_code, 2)
        self.assertIn("S1", [e for e in self.events("stage_finished") if e["stage"] == "release"][0]["detail"])

    def test_missing_sign_off_row_also_blocks(self):
        self.set_signoff({})
        self.assertEqual(self.orch(approval_pipeline()).run().stages["release"], BLOCKED)

    def test_signing_unblocks_release_without_rerunning_gates(self):
        counter = self.root / "n"
        p = pipeline(
            stage("schema", gates=[cmd(py(COUNT, counter))], autonomy="agent+human", approvals=["S1"]),
            stage("release", ["schema"], autonomy="agent+human", release_gate=True),
        )
        self.set_signoff({"S1": ("", "", "")})
        self.assertEqual(self.orch(p).run().status, "BLOCKED")
        self.set_signoff({"S1": ("Sanjeev", "2026-11-05", "Approved")})
        result = self.orch(p).run()
        self.assertEqual(result.status, "SUCCESS")
        self.assertEqual(result.stages["schema"], CACHED)  # technical gates not repeated, approval re-read
        self.assertEqual(counter.read_text(), "1")
        lineage = [e for e in self.events("stage_finished") if e["stage"] == "schema"][-1]["lineage"]
        self.assertIn("approved by Sanjeev", lineage["approvals"]["S1"])

    def test_rejection_fails_the_stage(self):
        self.set_signoff({"S1": ("Sanjeev", "2026-11-05", "Rejected: add a test")})
        result = self.orch(approval_pipeline()).run()
        self.assertEqual(result.stages["schema"], FAILED)
        self.assertEqual(result.status, "FAILED")

    def test_revoking_an_approval_blocks_release_again(self):
        self.set_signoff({"S1": ("Sanjeev", "2026-11-05", "Approved")})
        self.assertEqual(self.orch(approval_pipeline()).run().status, "SUCCESS")
        self.set_signoff({"S1": ("Sanjeev", "2026-11-06", "Rejected")})
        self.assertEqual(self.orch(approval_pipeline()).run().stages["schema"], FAILED)


# ----------------------------------------------------------------------------------------------------------------------
# Re-planning
# ----------------------------------------------------------------------------------------------------------------------


def chain_pipeline(counter_dir: Path):
    def counted(name, *inputs):
        return stage(name, gates=[cmd(py(COUNT, counter_dir / name))], inputs=list(inputs))

    return counted


class ReplanTests(Sandbox):
    def setUp(self):
        super().setUp()
        self.counts = self.root / "counts"
        self.counts.mkdir()
        self.write("req.md", "R1 create links")
        self.write("b.txt", "b v1")
        self.write("d.txt", "d v1")
        counted = chain_pipeline(self.counts)
        # a -> b -> c   and   a -> d   (d is a sibling branch)
        self.pipe = pipeline(
            counted("a", "req.md"),
            dict(counted("b", "b.txt"), depends_on=["a"]),
            dict(counted("c"), depends_on=["b"]),
            dict(counted("d", "d.txt"), depends_on=["a"]),
        )

    def count(self, name):
        f = self.counts / name
        return int(f.read_text()) if f.exists() else 0

    def test_second_run_reuses_everything(self):
        self.assertEqual(self.orch(self.pipe).run().status, "SUCCESS")
        second = self.orch(self.pipe).run()
        self.assertEqual(set(second.stages.values()), {CACHED})
        self.assertEqual([self.count(n) for n in "abcd"], [1, 1, 1, 1])

    def test_changed_input_reruns_only_that_stage_and_its_descendants(self):
        self.orch(self.pipe).run()
        self.write("b.txt", "b v2")
        result = self.orch(self.pipe).run()
        self.assertEqual(result.stages, {"a": CACHED, "b": PASSED, "c": PASSED, "d": CACHED})
        self.assertEqual([self.count(n) for n in "abcd"], [1, 2, 2, 1])
        stale = self.events("replan")[-1]["stale"]
        self.assertEqual(stale["b"]["changed_inputs"], ["b.txt"])
        self.assertEqual(stale["c"]["upstream_changed"], ["b"])
        self.assertEqual(set(stale), {"b", "c"})

    def test_changed_requirement_replans_the_whole_downstream_graph(self):
        self.orch(self.pipe).run()
        self.write("req.md", "R1 create links; R6 links may expire")
        result = self.orch(self.pipe).run()
        self.assertEqual(set(result.stages.values()), {PASSED})
        self.assertEqual(set(self.events("replan")[-1]["stale"]), {"a", "b", "c", "d"})

    def test_changed_stage_definition_is_detected(self):
        self.orch(self.pipe).run()
        counted = chain_pipeline(self.counts)
        changed = pipeline(
            counted("a", "req.md"),
            dict(counted("b", "b.txt"), depends_on=["a"], decisions=["D9"]),  # metadata change
            dict(counted("c"), depends_on=["b"]),
            dict(counted("d", "d.txt"), depends_on=["a"]),
        )
        self.orch(changed).run()
        stale = self.events("replan")[-1]["stale"]
        self.assertTrue(stale["b"]["definition_changed"])

    def test_a_failed_stage_is_not_cached(self):
        flag = self.root / "ok.flag"
        gate = cmd(py("import pathlib,sys; sys.exit(0 if pathlib.Path(sys.argv[1]).exists() else 1)", flag))
        p = pipeline(stage("a", gates=[gate]))
        self.assertEqual(self.orch(p).run().stages["a"], FAILED)
        flag.write_text("fixed")
        self.assertEqual(self.orch(p).run().stages["a"], PASSED)  # re-executed, not served from cache

    def test_plan_reports_state_without_writing(self):
        self.orch(self.pipe).run()
        self.write("b.txt", "b v2")
        before = len(self.events())
        plan = self.orch(self.pipe).plan()
        states = {s["id"]: s["state"] for s in plan["stages"]}
        self.assertEqual(states, {"a": "up-to-date", "b": "stale", "c": "stale", "d": "up-to-date"})
        self.assertEqual(len(self.events()), before)  # read-only


# ----------------------------------------------------------------------------------------------------------------------
# Ledger
# ----------------------------------------------------------------------------------------------------------------------


class LedgerTests(Sandbox):
    def run_once(self):
        p = pipeline(
            stage("a", decisions=["D3"], ai_log=[5]),
            stage("b", ["a"], gates=[cmd(py(COUNT, self.root / "n"), transient=True)]),
        )
        self.orch(p).run()
        return self.root / "orchestration" / "ledger"

    def test_untouched_ledger_verifies(self):
        self.assertEqual(verify_ledger(self.run_once()), [])

    def test_modified_record_is_detected(self):
        d = self.run_once()
        f = next(d.glob("*.jsonl"))
        f.write_text(f.read_text().replace('"status":"pass"', '"status":"fail"').replace('"status": "pass"', '"status": "fail"'))
        problems = verify_ledger(d)
        self.assertTrue(any("modified" in p for p in problems), problems)

    def test_removed_record_is_detected(self):
        d = self.run_once()
        f = next(d.glob("*.jsonl"))
        lines = f.read_text().splitlines()
        del lines[3]
        f.write_text("\n".join(lines) + "\n")
        problems = verify_ledger(d)
        self.assertTrue(any("chain broken" in p or "sequence gap" in p for p in problems), problems)

    def test_stage_records_carry_decision_lineage(self):
        self.run_once()
        a = [e for e in self.events("stage_finished") if e["stage"] == "a"][0]
        b = [e for e in self.events("stage_finished") if e["stage"] == "b"][0]
        self.assertEqual(a["lineage"]["decisions"], ["D3"])
        self.assertEqual(a["lineage"]["ai_log"], [5])
        self.assertEqual(list(b["lineage"]["depends_on"]), ["a"])  # b records exactly which upstream version it consumed
        self.assertEqual(b["lineage"]["depends_on"]["a"], a["fingerprint"][:12])

    def test_events_are_ordered_and_hash_linked(self):
        self.run_once()
        evs = self.events()
        self.assertEqual([e["seq"] for e in evs], list(range(len(evs))))
        self.assertEqual(evs[0]["event"], "run_started")
        self.assertEqual(evs[-1]["event"], "run_finished")


# ----------------------------------------------------------------------------------------------------------------------
# Metrics
# ----------------------------------------------------------------------------------------------------------------------

T0 = datetime(2026, 11, 4, 12, 0, 0, tzinfo=timezone.utc)


def ev(run, kind, offset, **fields):
    return {"run": run, "event": kind, "ts": (T0 + timedelta(seconds=offset)).isoformat(timespec="milliseconds"), **fields}


class MetricsTests(unittest.TestCase):
    def synthetic(self):
        return [
            # run 1: stage x fails after a retry, rollback recommended; 10 s wall
            ev("r1", "run_started", 0),
            ev("r1", "gate_retry", 1, stage="x"),
            ev("r1", "gate_result", 2, stage="x", phase="exit", attempts=2, status="fail"),
            ev("r1", "stage_finished", 3, stage="x", status=FAILED, executed=True, duration=3.0, retried=True),
            ev("r1", "rollback_recommended", 3, stage="x"),
            ev("r1", "run_finished", 10, status="FAILED"),
            # run 2, 100 s later: x repaired; y passes first time; 6 s wall; x was re-planned
            ev("r2", "run_started", 100),
            ev("r2", "replan", 100, stale={"x": {}}),
            ev("r2", "gate_result", 103, stage="x", phase="exit", attempts=1, status="pass"),
            ev("r2", "stage_finished", 104, stage="x", status=PASSED, executed=True, duration=4.0),
            ev("r2", "gate_result", 105, stage="y", phase="exit", attempts=1, status="pass"),
            ev("r2", "stage_finished", 105, stage="y", status=PASSED, executed=True, duration=2.0),
            ev("r2", "stage_finished", 105, stage="z", status=CACHED, executed=False),
            ev("r2", "run_finished", 106, status="SUCCESS"),
        ]

    def test_values(self):
        m = compute_metrics(self.synthetic())
        self.assertEqual(m["runs"], 2)
        self.assertEqual(m["run_outcomes"], {"FAILED": 1, "SUCCESS": 1})
        self.assertAlmostEqual(m["stage_success_rate"], 2 / 3)  # x failed once; x and y passed; cached z excluded
        self.assertAlmostEqual(m["first_attempt_rate"], 2 / 3)
        self.assertEqual(m["retries"], 1)
        self.assertAlmostEqual(m["retry_frequency"], 1 / 4)  # 1 retry over 2+1+1 gate attempts
        self.assertEqual(m["rollbacks_recommended"], 1)
        self.assertEqual(m["rollbacks_executed"], 0)
        self.assertEqual(m["replans"], 1)
        self.assertAlmostEqual(m["mttr_seconds"], 101.0)  # x failed at +3, passed at +104
        self.assertEqual(m["open_failures"], [])
        self.assertAlmostEqual(m["e2e_mean_seconds"], 8.0)
        self.assertAlmostEqual(m["e2e_median_seconds"], 8.0)
        self.assertAlmostEqual(m["e2e_max_seconds"], 10.0)
        self.assertAlmostEqual(m["parallel_gain"], (3 + 4 + 2) / 16)

    def test_unrepaired_failure_is_reported_open(self):
        events = [e for e in self.synthetic() if e["run"] == "r1"]
        m = compute_metrics(events)
        self.assertIsNone(m["mttr_seconds"])
        self.assertEqual(m["open_failures"], ["x"])

    def test_empty_ledger(self):
        m = compute_metrics([])
        self.assertEqual(m["runs"], 0)
        self.assertIsNone(m["stage_success_rate"])
        self.assertIn("n/a", engine.render_metrics_md(m))

    def test_blocked_stages_do_not_count_as_failures(self):
        events = [ev("r", "run_started", 0), ev("r", "stage_finished", 1, stage="a", status=BLOCKED, executed=True, duration=1.0), ev("r", "run_finished", 2, status="BLOCKED")]
        self.assertIsNone(compute_metrics(events)["stage_success_rate"])


class MetricsFromRealRunTests(Sandbox):
    def test_metrics_after_a_real_run(self):
        counter = self.root / "n"
        p = pipeline(stage("a", gates=[cmd(py(FAIL_UNTIL, counter, 1), transient=True, retries=2)]), stage("b", ["a"]))
        self.orch(p).run()
        m = compute_metrics(self.events())
        self.assertEqual(m["retries"], 1)
        self.assertEqual(m["stage_success_rate"], 1.0)
        self.assertEqual(m["first_attempt_rate"], 0.5)  # a needed a retry, b did not
        self.assertGreater(m["e2e_mean_seconds"], 0)


# ----------------------------------------------------------------------------------------------------------------------
# The real pipeline against the real repository
# ----------------------------------------------------------------------------------------------------------------------


class RealPipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pipe = Pipeline.load(HERE / "pipeline.json")

    def test_definition_is_valid_and_has_parallel_paths_and_joins(self):
        self.assertTrue(any(len(w) > 1 for w in self.pipe.waves()), "no parallel paths")
        self.assertTrue(any(len(s.depends_on) > 1 for s in self.pipe.stages.values()), "no join stage")

    def test_every_input_pattern_matches_a_file(self):
        for st in self.pipe.stages.values():
            for pattern in st.inputs:
                self.assertTrue(engine.expand(PROJECT, pattern), f"{st.id}: input {pattern!r} matches no file")

    def test_ai_log_and_decision_references_exist(self):
        ai_log = (PROJECT / "docs" / "AI-LOG.md").read_text(encoding="utf-8")
        entries = {int(n) for n in re.findall(r"^### (\d+)\. ", ai_log, re.M)}
        decisions = set(re.findall(r"^## (D\d+)\.", (PROJECT / "docs" / "DECISIONS.md").read_text(encoding="utf-8"), re.M))
        for st in self.pipe.stages.values():
            self.assertLessEqual(set(st.ai_log), entries, f"{st.id}: unknown AI-LOG entry")
            self.assertLessEqual(set(st.decisions), decisions, f"{st.id}: unknown decision")

    def test_approval_items_exist_in_the_signoff_table(self):
        table = read_signoff(PROJECT / "docs" / "SIGNOFF.md")
        for st in self.pipe.stages.values():
            for item in st.approvals:
                self.assertIn(item, table, f"{st.id}: {item} is not a row of docs/SIGNOFF.md")

    def test_gradle_test_filters_name_real_test_classes(self):
        classes = {p.stem for p in (PROJECT / "src" / "test").rglob("*.java")}
        for st in self.pipe.stages.values():
            for gate in st.exit_gates + (st.fallback or {}).get("exit_gates", []):
                argv = gate.get("argv", [])
                for i, arg in enumerate(argv):
                    if i and argv[i - 1] == "--tests":
                        self.assertIn(arg.lstrip("*"), classes, f"{st.id}: no test class {arg}")

    def test_commands_point_at_files_that_exist(self):
        for st in self.pipe.stages.values():
            for gate in st.entry_gates + st.exit_gates:
                if gate["type"] == "command" and gate["argv"][0].startswith(("./", "scripts/")):
                    if gate["argv"][0] == "./gradlew":
                        continue  # the wrapper (script + jar) is not in every copy; the 'file_exists gradlew' entry gate guards it at run time
                    self.assertTrue((PROJECT / gate["argv"][0]).exists(), f"{st.id}: {gate['argv'][0]} not found")

    def test_policy_preflight_passes_on_this_repository(self):
        orch = Orchestrator(PROJECT, self.pipe, ledger_dir=Path(tempfile.mkdtemp()), log=lambda s: None)
        bad = [p for p in orch._preflight() if p[1] != "pass"]
        self.assertEqual(bad, [])

    def test_documentation_gate_passes_on_this_repository(self):
        docs = self.pipe.stages["docs"]
        for gate in docs.exit_gates:
            status, detail = check_gate(gate, PROJECT, dict(os.environ), LockTable())
            self.assertEqual(status, "pass", f"{gate.get('name')}: {detail}")

    def test_plan_cli_runs(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cli.main(["plan"])
        self.assertEqual(code, 0)
        self.assertIn("(parallel)", out.getvalue())

    def test_graph_cli_prints_mermaid(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(cli.main(["graph"]), 0)
        self.assertTrue(out.getvalue().startswith("flowchart LR"))
        self.assertIn("quality --> smoke", out.getvalue())

    def test_committed_ledger_verifies(self):
        self.assertEqual(verify_ledger(PROJECT / "orchestration" / "ledger"), [])


if __name__ == "__main__":
    unittest.main()
