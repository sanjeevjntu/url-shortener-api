"""Control plane for the URL shortener delivery pipeline (standard library only, Python 3.9+).

The engine does not write code. It *governs* the work an AI agent does: it reads a stage graph, runs each stage's entry
and exit gates, enforces human approvals, retries and falls back within bounds, stops safely, records every decision in a
hash-chained ledger and re-plans when an upstream input changes. See orchestration/README.md.
"""

from __future__ import annotations

import concurrent.futures as cf
import dataclasses
import hashlib
import json
import os
import re
import secrets
import shutil
import statistics
import subprocess
import threading
import time
import urllib.parse
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

# --------------------------------------------------------------------------------------------------------------------
# Statuses
# --------------------------------------------------------------------------------------------------------------------

PASSED = "PASSED"  # gates ran and passed, approvals (if any) granted
CACHED = "CACHED"  # inputs unchanged since the last pass: gates not re-run
DEGRADED = "DEGRADED"  # primary gates failed; the declared fallback passed
AWAITING = "AWAITING_APPROVAL"  # technically passed; a human has not signed yet
FAILED = "FAILED"
BLOCKED = "BLOCKED"  # a precondition or sign-off is missing; needs a human or the environment
SKIPPED = "SKIPPED"  # not started: an upstream stage did not pass, or the run was stopped
TECHNICAL_OK = {PASSED, CACHED, DEGRADED, AWAITING}

GATE_REQUIRED = {
    "command": ["argv"],
    "file_exists": ["path"],
    "file_contains": ["path", "pattern"],
    "file_hash": ["path", "sha256"],
    "md_links": ["include"],
    "forbidden_text": ["include", "patterns"],
    "tool_available": ["tool"],
    "env_present": ["var"],
}
AUTONOMY = {"agent", "agent+human"}
SKIP_DIRS = {".git", "build", ".gradle", "__pycache__", "node_modules", ".backup", ".idea"}


class PipelineError(Exception):
    """The pipeline definition is invalid."""


# --------------------------------------------------------------------------------------------------------------------
# Pipeline definition and graph
# --------------------------------------------------------------------------------------------------------------------


@dataclasses.dataclass
class Stage:
    id: str
    title: str
    depends_on: List[str]
    autonomy: str
    inputs: List[str]
    entry_gates: List[dict]
    exit_gates: List[dict]
    approvals: List[str]
    fallback: Optional[dict]
    rollback: Optional[dict]
    release_gate: bool
    ai_log: List[int]
    decisions: List[str]
    raw: dict


class Pipeline:
    def __init__(self, name: str, policy: dict, stages: "Dict[str, Stage]"):
        self.name = name
        self.policy = policy
        self.stages = stages

    @classmethod
    def from_dict(cls, data: dict) -> "Pipeline":
        stages: Dict[str, Stage] = {}
        for raw in data.get("stages", []):
            sid = raw.get("id")
            if not sid:
                raise PipelineError("a stage without an id")
            if sid in stages:
                raise PipelineError(f"duplicate stage id: {sid}")
            stages[sid] = Stage(
                id=sid,
                title=raw.get("title", sid),
                depends_on=list(raw.get("depends_on", [])),
                autonomy=raw.get("autonomy", "agent"),
                inputs=list(raw.get("inputs", [])),
                entry_gates=list(raw.get("entry_gates", [])),
                exit_gates=list(raw.get("exit_gates", [])),
                approvals=list(raw.get("approvals", [])),
                fallback=raw.get("fallback"),
                rollback=raw.get("rollback"),
                release_gate=bool(raw.get("release_gate", False)),
                ai_log=list(raw.get("ai_log", [])),
                decisions=list(raw.get("decisions", [])),
                raw=raw,
            )
        policy = dict(data.get("policy", {}))
        pipeline = cls(data.get("name", "pipeline"), policy, stages)
        pipeline.validate()
        return pipeline

    @classmethod
    def load(cls, path: Path) -> "Pipeline":
        return cls.from_dict(json.loads(Path(path).read_text(encoding="utf-8")))

    # -- validation ------------------------------------------------------------------------------------------------

    def validate(self) -> None:
        if not self.stages:
            raise PipelineError("pipeline has no stages")
        max_retries = self.policy.get("max_retries", 2)
        if not isinstance(max_retries, int) or not 0 <= max_retries <= 5:
            raise PipelineError("policy.max_retries must be an integer between 0 and 5")
        for st in self.stages.values():
            for dep in st.depends_on:
                if dep == st.id:
                    raise PipelineError(f"stage {st.id} depends on itself")
                if dep not in self.stages:
                    raise PipelineError(f"stage {st.id} depends on unknown stage {dep}")
            if st.autonomy not in AUTONOMY:
                raise PipelineError(f"stage {st.id}: autonomy must be one of {sorted(AUTONOMY)}")
            if st.approvals and st.autonomy != "agent+human":
                raise PipelineError(f"stage {st.id}: approvals require autonomy 'agent+human'")
            if st.autonomy == "agent+human" and not st.approvals and not st.release_gate:
                raise PipelineError(f"stage {st.id}: 'agent+human' needs approvals or release_gate")
            gates = st.entry_gates + st.exit_gates + (st.fallback or {}).get("exit_gates", [])
            for gate in gates:
                self._validate_gate(st.id, gate)
            if st.fallback and not st.fallback.get("exit_gates"):
                raise PipelineError(f"stage {st.id}: fallback needs exit_gates")
        for gate in self.policy.get("preflight", []):
            self._validate_gate("policy.preflight", gate)
        self.topological_order()  # raises on a cycle

    @staticmethod
    def _validate_gate(where: str, gate: dict) -> None:
        kind = gate.get("type")
        if kind not in GATE_REQUIRED:
            raise PipelineError(f"{where}: unknown gate type {kind!r}")
        for key in GATE_REQUIRED[kind]:
            if key not in gate:
                raise PipelineError(f"{where}: gate {gate.get('name', kind)!r} is missing {key!r}")
        if kind == "command" and (not isinstance(gate["argv"], list) or not gate["argv"]):
            raise PipelineError(f"{where}: command gate needs a non-empty argv list")

    # -- graph -----------------------------------------------------------------------------------------------------

    def topological_order(self) -> List[str]:
        """Dependency order, ties broken by declaration order (stable and deterministic)."""
        indegree = {sid: len(st.depends_on) for sid, st in self.stages.items()}
        children: Dict[str, List[str]] = defaultdict(list)
        for sid, st in self.stages.items():
            for dep in st.depends_on:
                children[dep].append(sid)
        declared = list(self.stages)
        ready = [sid for sid in declared if indegree[sid] == 0]
        order: List[str] = []
        while ready:
            sid = ready.pop(0)
            order.append(sid)
            for child in children[sid]:
                indegree[child] -= 1
                if indegree[child] == 0:
                    ready.append(child)
            ready.sort(key=declared.index)
        if len(order) != len(self.stages):
            stuck = sorted(set(self.stages) - set(order))
            raise PipelineError(f"dependency cycle involving: {', '.join(stuck)}")
        return order

    def waves(self) -> List[List[str]]:
        """Stages grouped by depth. Stages in one wave have no dependency on each other and may run in parallel."""
        level: Dict[str, int] = {}
        for sid in self.topological_order():
            deps = self.stages[sid].depends_on
            level[sid] = 1 + max((level[d] for d in deps), default=-1)
        out: List[List[str]] = [[] for _ in range(max(level.values()) + 1)]
        for sid in self.topological_order():
            out[level[sid]].append(sid)
        return out

    def ancestors(self, sid: str) -> set:
        seen: set = set()
        todo = list(self.stages[sid].depends_on)
        while todo:
            cur = todo.pop()
            if cur not in seen:
                seen.add(cur)
                todo.extend(self.stages[cur].depends_on)
        return seen

    def descendants(self, sid: str) -> set:
        return {other for other in self.stages if sid in self.ancestors(other)}

    def required_approvals(self, sid: str) -> List[str]:
        items = set(self.stages[sid].approvals)
        for anc in self.ancestors(sid):
            items.update(self.stages[anc].approvals)
        return sorted(items)


# --------------------------------------------------------------------------------------------------------------------
# Files, fingerprints
# --------------------------------------------------------------------------------------------------------------------


def _skipped(path: Path, root: Path) -> bool:
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        return True
    return any(p in SKIP_DIRS for p in parts)


def expand(root: Path, pattern: str) -> List[Path]:
    """Files matching a glob. 'dir/**' means every file below dir."""
    if pattern.endswith("/**"):
        base = root / pattern[:-3]
        found = [p for p in base.rglob("*") if p.is_file()] if base.is_dir() else []
    else:
        found = [p for p in root.glob(pattern) if p.is_file()]
    return sorted(p for p in found if not _skipped(p, root))


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_inputs(root: Path, patterns: List[str]) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for pattern in patterns:
        for path in expand(root, pattern):
            out[path.relative_to(root).as_posix()] = file_sha256(path)
    return dict(sorted(out.items()))


def fingerprint(stage: Stage, digests: Dict[str, str], dep_fps: Dict[str, str]) -> str:
    """Merkle-style: own definition + own input files + the fingerprints of every dependency."""
    payload = {"def": stage.raw, "inputs": digests, "deps": dep_fps}
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


# --------------------------------------------------------------------------------------------------------------------
# Gates
# --------------------------------------------------------------------------------------------------------------------


@dataclasses.dataclass
class GateResult:
    name: str
    type: str
    status: str  # pass | fail | blocked
    detail: str = ""
    attempts: int = 1
    duration: float = 0.0


def _slug(heading: str) -> str:
    """GitHub-style heading anchor."""
    text = re.sub(r"[^\w\- ]", "", heading.strip().lower(), flags=re.UNICODE)
    return text.replace(" ", "-")


_FENCE = re.compile(r"(?ms)^(```|~~~).*?^\1[^\n]*$")
_INLINE = re.compile(r"`[^`\n]*`")
_LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
_HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*#*\s*$", re.M)


def _headings(text: str) -> set:
    return {_slug(m.group(1)) for m in _HEADING.finditer(_FENCE.sub("", text))}


def _check_md_links(gate: dict, root: Path) -> Tuple[str, str]:
    problems: List[str] = []
    files: List[Path] = []
    for pattern in gate["include"]:
        files.extend(expand(root, pattern))
    for file in sorted(set(files)):
        text = _FENCE.sub("", file.read_text(encoding="utf-8"))
        for match in _LINK.finditer(_INLINE.sub("", text)):
            target = urllib.parse.unquote(match.group(1))
            if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith("//"):
                continue
            path_part, _, anchor = target.partition("#")
            dest = file if not path_part else (file.parent / path_part).resolve()
            rel = file.relative_to(root).as_posix()
            if not dest.exists():
                problems.append(f"{rel}: missing target {target}")
            elif anchor and dest.suffix == ".md" and anchor not in _headings(dest.read_text(encoding="utf-8")):
                problems.append(f"{rel}: no heading for #{anchor} in {dest.name}")
    if problems:
        return "fail", f"{len(problems)} broken link(s): " + "; ".join(problems[:8])
    return "pass", f"{len(set(files))} file(s) checked"


def _check_forbidden(gate: dict, root: Path) -> Tuple[str, str]:
    patterns = [re.compile(p) for p in gate["patterns"]]
    hits: List[str] = []
    seen: set = set()
    for pattern in gate["include"]:
        for path in expand(root, pattern):
            if path in seen or path.stat().st_size > 2_000_000:
                continue
            seen.add(path)
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for number, line in enumerate(text.splitlines(), 1):
                for rx in patterns:
                    if rx.search(line):
                        hits.append(f"{path.relative_to(root).as_posix()}:{number} matches /{rx.pattern}/")
    if hits:
        return "fail", f"{len(hits)} forbidden match(es): " + "; ".join(hits[:5])
    return "pass", f"{len(seen)} file(s) scanned"


def _expand_args(argv: List[str], env: Dict[str, str], root: Path) -> List[str]:
    out = [re.sub(r"\$\{(\w+)\}", lambda m: env.get(m.group(1), ""), a) for a in argv]
    first = out[0]
    if "/" in first and not os.path.isabs(first) and (root / first).exists():
        out[0] = str(root / first)
    return out


def _check_command(gate: dict, root: Path, env: Dict[str, str], locks: "LockTable") -> Tuple[str, str]:
    argv = _expand_args(gate["argv"], env, root)
    timeout = gate.get("timeout", 600)
    try:
        with locks.get(gate.get("exclusive")):
            proc = subprocess.run(
                argv, cwd=root, env=env, capture_output=True, text=True, timeout=timeout, check=False
            )
    except FileNotFoundError:
        return "blocked", f"command not found: {argv[0]}"
    except subprocess.TimeoutExpired:
        return "fail", f"timed out after {timeout}s"
    tail = (proc.stdout + proc.stderr).strip()[-1200:]
    if proc.returncode == 0:
        return "pass", "exit 0"
    return "fail", f"exit {proc.returncode}: {tail}"


class LockTable:
    """Named locks: gates sharing an `exclusive` name (e.g. gradle) are serialised; everything else runs in parallel."""

    def __init__(self) -> None:
        self._guard = threading.Lock()
        self._locks: Dict[str, threading.Lock] = {}

    def get(self, name: Optional[str]):
        if not name:
            return _NullLock()
        with self._guard:
            return self._locks.setdefault(name, threading.Lock())


class _NullLock:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def check_gate(gate: dict, root: Path, env: Dict[str, str], locks: LockTable) -> Tuple[str, str]:
    kind = gate["type"]
    if kind == "command":
        return _check_command(gate, root, env, locks)
    if kind == "file_exists":
        ok = (root / gate["path"]).exists()
        return ("pass", "exists") if ok else ("fail", f"missing {gate['path']}")
    if kind == "file_contains":
        path = root / gate["path"]
        if not path.exists():
            return "fail", f"missing {gate['path']}"
        count = len(re.findall(gate["pattern"], path.read_text(encoding="utf-8"), re.M))
        need = gate.get("min_count", 1)
        return ("pass", f"{count} match(es)") if count >= need else ("fail", f"{count} match(es), need {need}")
    if kind == "file_hash":
        path = root / gate["path"]
        if not path.exists():
            return "fail", f"missing {gate['path']}"
        actual = file_sha256(path)
        if actual.startswith(gate["sha256"]):
            return "pass", "hash matches"
        return "fail", f"{gate['path']} changed (sha256 {actual[:12]}, expected {gate['sha256'][:12]})"
    if kind == "md_links":
        return _check_md_links(gate, root)
    if kind == "forbidden_text":
        return _check_forbidden(gate, root)
    if kind == "tool_available":
        found = shutil.which(gate["tool"]) or ((root / gate["tool"]).exists() and gate["tool"])
        return ("pass", str(found)) if found else ("blocked", f"{gate['tool']} not found on PATH")
    if kind == "env_present":
        return ("pass", "set") if env.get(gate["var"]) else ("blocked", f"environment variable {gate['var']} is not set")
    return "fail", f"unknown gate type {kind}"


# --------------------------------------------------------------------------------------------------------------------
# Human approvals (docs/SIGNOFF.md)
# --------------------------------------------------------------------------------------------------------------------

_SIGNOFF_ROW = re.compile(r"^\|\s*(S\d+)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|\s*$", re.M)


def read_signoff(path: Path) -> Dict[str, Tuple[str, str]]:
    """item -> (state, detail); state is granted | pending | rejected. Items not in the table are absent."""
    if not path.exists():
        return {}
    out: Dict[str, Tuple[str, str]] = {}
    for m in _SIGNOFF_ROW.finditer(path.read_text(encoding="utf-8")):
        item, reviewer, date, decision = (m.group(1), m.group(2).strip(), m.group(3).strip(), m.group(4).strip())
        if reviewer and re.search(r"(?i)reject|declin", decision):
            out[item] = ("rejected", f"{item} rejected by {reviewer} on {date or '?'}: {decision}")
        elif reviewer and date and re.match(r"(?i)\s*approv", decision):
            out[item] = ("granted", f"{item} approved by {reviewer} on {date}")
        else:
            out[item] = ("pending", f"{item} awaiting sign-off")
    return out


# --------------------------------------------------------------------------------------------------------------------
# Ledger: append-only JSONL with a hash chain (tamper-evident)
# --------------------------------------------------------------------------------------------------------------------

GENESIS = "0" * 64


def _canonical(rec: dict) -> str:
    return json.dumps(rec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class Ledger:
    def __init__(self, directory: Path, run_id: str, clock: Callable[[], datetime]):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.run_id = run_id
        self.path = self.directory / f"{run_id}.jsonl"
        self._clock = clock
        self._lock = threading.Lock()
        self._seq = 0
        self._prev = GENESIS

    def append(self, event: str, **fields) -> dict:
        with self._lock:
            rec = {
                "seq": self._seq,
                "ts": self._clock().isoformat(timespec="milliseconds"),
                "run": self.run_id,
                "event": event,
                **fields,
                "prev": self._prev,
            }
            digest = hashlib.sha256(_canonical(rec).encode()).hexdigest()
            line = json.dumps({**rec, "hash": digest}, sort_keys=True, ensure_ascii=False)
            with self.path.open("a", encoding="utf-8") as fh:
                fh.write(line + "\n")
            self._seq += 1
            self._prev = digest
            return rec


def read_events(directory: Path) -> List[dict]:
    events: List[dict] = []
    directory = Path(directory)
    if not directory.is_dir():
        return events
    for file in sorted(directory.glob("*.jsonl")):
        for line in file.read_text(encoding="utf-8").splitlines():
            if line.strip():
                events.append(json.loads(line))
    return events


def verify_ledger(directory: Path) -> List[str]:
    """Empty list = every run file is an unbroken, untampered chain."""
    problems: List[str] = []
    directory = Path(directory)
    for file in sorted(directory.glob("*.jsonl")) if directory.is_dir() else []:
        prev, seq = GENESIS, 0
        for number, line in enumerate(file.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                rec = json.loads(line)
                claimed = rec.pop("hash")
            except (ValueError, KeyError):
                problems.append(f"{file.name}:{number}: unreadable record")
                break
            if rec.get("prev") != prev:
                problems.append(f"{file.name}:{number}: chain broken (prev hash does not match)")
            if rec.get("seq") != seq:
                problems.append(f"{file.name}:{number}: sequence gap (expected {seq}, found {rec.get('seq')})")
            if hashlib.sha256(_canonical(rec).encode()).hexdigest() != claimed:
                problems.append(f"{file.name}:{number}: content was modified")
            prev, seq = claimed, seq + 1
    return problems


# --------------------------------------------------------------------------------------------------------------------
# Metrics (computed from the ledger only)
# --------------------------------------------------------------------------------------------------------------------


def _ts(value: str) -> datetime:
    return datetime.fromisoformat(value)


def compute_metrics(events: List[dict]) -> dict:
    runs: Dict[str, dict] = {}
    for ev in events:
        run = runs.setdefault(ev["run"], {"start": None, "end": None, "status": None, "stage_seconds": 0.0})
        if ev["event"] == "run_started":
            run["start"] = _ts(ev["ts"])
        elif ev["event"] == "run_finished":
            run["end"] = _ts(ev["ts"])
            run["status"] = ev.get("status")
        elif ev["event"] == "stage_finished" and ev.get("executed"):
            run["stage_seconds"] += ev.get("duration", 0.0)

    finished = [r for r in runs.values() if r["start"] and r["end"]]
    latencies = [(r["end"] - r["start"]).total_seconds() for r in finished]

    executed = [e for e in events if e["event"] == "stage_finished" and e.get("executed") and e["status"] != BLOCKED]
    passed = [e for e in executed if e["status"] in (PASSED, DEGRADED, AWAITING)]
    first_try = [e for e in passed if not e.get("retried") and not e.get("fallback")]
    gate_runs = [e for e in events if e["event"] == "gate_result" and e.get("phase") in ("exit", "fallback")]
    gate_attempts = sum(e.get("attempts", 1) for e in gate_runs)
    retries = [e for e in events if e["event"] == "gate_retry"]
    rollbacks = [e for e in events if e["event"] == "rollback_executed"]
    recommended = [e for e in events if e["event"] == "rollback_recommended"]
    fallbacks = [e for e in events if e["event"] == "fallback"]
    replans = [e for e in events if e["event"] == "replan" and e.get("stale")]

    # MTTR: from a stage's first failing execution to its next passing execution.
    repairs: List[float] = []
    open_failures: List[str] = []
    by_stage: Dict[str, List[dict]] = defaultdict(list)
    for e in executed:
        by_stage[e["stage"]].append(e)
    for stage, items in by_stage.items():
        failed_at: Optional[datetime] = None
        for e in items:
            if e["status"] == FAILED and failed_at is None:
                failed_at = _ts(e["ts"])
            elif e["status"] in (PASSED, DEGRADED, AWAITING) and failed_at is not None:
                repairs.append((_ts(e["ts"]) - failed_at).total_seconds())
                failed_at = None
        if failed_at is not None:
            open_failures.append(stage)

    wall = sum(latencies)
    outcomes: Dict[str, int] = defaultdict(int)
    for r in finished:
        outcomes[r["status"] or "UNKNOWN"] += 1

    return {
        "runs": len(runs),
        "runs_finished": len(finished),
        "run_outcomes": dict(outcomes),
        "stage_executions": len(executed),
        "stage_success_rate": (len(passed) / len(executed)) if executed else None,
        "first_attempt_rate": (len(first_try) / len(executed)) if executed else None,
        "gate_attempts": gate_attempts,
        "retries": len(retries),
        "retry_frequency": (len(retries) / gate_attempts) if gate_attempts else None,
        "rollbacks_executed": len(rollbacks),
        "rollbacks_recommended": len(recommended),
        "rollback_frequency_per_run": (len(rollbacks) / len(runs)) if runs else None,
        "fallbacks": len(fallbacks),
        "replans": len(replans),
        "mttr_seconds": statistics.mean(repairs) if repairs else None,
        "repairs": len(repairs),
        "open_failures": sorted(open_failures),
        "e2e_mean_seconds": statistics.mean(latencies) if latencies else None,
        "e2e_median_seconds": statistics.median(latencies) if latencies else None,
        "e2e_max_seconds": max(latencies) if latencies else None,
        "parallel_gain": (sum(r["stage_seconds"] for r in finished) / wall) if wall else None,
    }


def _fmt(value, kind: str = "num") -> str:
    if value is None:
        return "n/a"
    if kind == "pct":
        return f"{value * 100:.1f}%"
    if kind == "sec":
        return f"{value:.1f} s"
    if kind == "x":
        return f"{value:.2f}x"
    return str(value)


def render_metrics_md(m: dict) -> str:
    rows = [
        ("Runs (finished)", f"{m['runs']} ({m['runs_finished']})", "Every invocation of `run.py run`"),
        ("Run outcomes", ", ".join(f"{k}: {v}" for k, v in sorted(m["run_outcomes"].items())) or "n/a", ""),
        ("Stage success rate", _fmt(m["stage_success_rate"], "pct"), "Executed stages whose gates passed (cached stages excluded)"),
        ("First-attempt rate", _fmt(m["first_attempt_rate"], "pct"), "Passed with no retry and no fallback"),
        ("Retry frequency", _fmt(m["retry_frequency"], "pct"), f"{m['retries']} retries over {m['gate_attempts']} gate attempts"),
        ("Rollbacks executed", f"{m['rollbacks_executed']} ({_fmt(m['rollback_frequency_per_run'])} per run)", f"{m['rollbacks_recommended']} recommended"),
        ("Fallbacks used", str(m["fallbacks"]), "Stages that passed only through their declared fallback"),
        ("Re-plans", str(m["replans"]), "Runs in which an upstream change made stages stale"),
        ("MTTR", _fmt(m["mttr_seconds"], "sec"), f"{m['repairs']} repair(s); open failures: {', '.join(m['open_failures']) or 'none'}"),
        ("End-to-end latency (mean / median / max)", " / ".join(_fmt(m[k], "sec") for k in ("e2e_mean_seconds", "e2e_median_seconds", "e2e_max_seconds")), "Wall time per finished run"),
        ("Parallel gain", _fmt(m["parallel_gain"], "x"), "Sum of stage durations divided by wall time (above 1.0 = stages overlapped)"),
    ]
    lines = ["| Metric | Value | Note |", "|---|---|---|"]
    lines += [f"| {a} | {b} | {c} |" for a, b, c in rows]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------------------------------------------------
# Orchestrator
# --------------------------------------------------------------------------------------------------------------------


@dataclasses.dataclass
class Outcome:
    status: str = PASSED
    detail: str = ""
    executed: bool = False
    gates_passed: bool = False
    fallback: bool = False
    retried: bool = False
    approvals: Dict[str, str] = dataclasses.field(default_factory=dict)
    duration: float = 0.0


@dataclasses.dataclass
class RunResult:
    run_id: str
    status: str  # SUCCESS | AWAITING_APPROVAL | BLOCKED | FAILED | ABORTED
    stages: Dict[str, str]

    @property
    def exit_code(self) -> int:
        return {"SUCCESS": 0, "FAILED": 1, "BLOCKED": 2, "AWAITING_APPROVAL": 2, "ABORTED": 3}[self.status]


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Orchestrator:
    def __init__(
        self,
        root: Path,
        pipeline: Pipeline,
        *,
        ledger_dir: Optional[Path] = None,
        clock: Callable[[], datetime] = utc_now,
        sleep: Callable[[float], None] = time.sleep,
        env: Optional[Dict[str, str]] = None,
        workers: Optional[int] = None,
        keep_going: bool = False,
        rollback: bool = False,
        tag_green: bool = False,
        only: Optional[str] = None,
        log: Callable[[str], None] = print,
    ):
        self.root = Path(root)
        self.pipeline = pipeline
        self.ledger_dir = Path(ledger_dir) if ledger_dir else self.root / "orchestration" / "ledger"
        self.clock = clock
        self.sleep = sleep
        self.env = dict(os.environ if env is None else env)
        self.workers = max(1, workers or pipeline.policy.get("max_workers", 3))
        self.keep_going = keep_going
        self.rollback = rollback
        self.tag_green = tag_green
        self.only = only
        self.log = log
        self.locks = LockTable()
        self.ledger: Optional[Ledger] = None
        self._fps: Dict[str, str] = {}
        self._prev: Dict[str, dict] = {}
        self._stopped = False
        self.signoff_path = self.root / pipeline.policy.get("signoff_file", "docs/SIGNOFF.md")
        if only and only not in pipeline.stages:
            raise PipelineError(f"unknown stage: {only}")

    # -- planning --------------------------------------------------------------------------------------------------

    def _selected(self) -> set:
        if not self.only:
            return set(self.pipeline.stages)
        return {self.only} | self.pipeline.ancestors(self.only)

    def _current(self) -> Tuple[Dict[str, str], Dict[str, Dict[str, str]]]:
        fps: Dict[str, str] = {}
        inputs: Dict[str, Dict[str, str]] = {}
        for sid in self.pipeline.topological_order():
            st = self.pipeline.stages[sid]
            inputs[sid] = resolve_inputs(self.root, st.inputs)
            fps[sid] = fingerprint(st, inputs[sid], {d: fps[d] for d in st.depends_on})
        return fps, inputs

    def _previous(self) -> Dict[str, dict]:
        """Last time each stage passed its *primary* gates. A fallback pass (DEGRADED) is deliberately not reusable:
        caching it would turn 'shipped through the fallback' into a clean pass on the next run."""
        state: Dict[str, dict] = {}
        for ev in read_events(self.ledger_dir):
            if ev.get("event") == "stage_finished" and ev.get("gates_passed") and not ev.get("fallback"):
                state[ev["stage"]] = ev
        return state

    def _replan_summary(self, fps, inputs, prev) -> Tuple[dict, List[str], List[str]]:
        stale: Dict[str, dict] = {}
        reused: List[str] = []
        new: List[str] = []
        for sid in self.pipeline.topological_order():
            p = prev.get(sid)
            if p is None:
                new.append(sid)
            elif p["fingerprint"] == fps[sid]:
                reused.append(sid)
            else:
                old = p.get("lineage", {}).get("inputs", {})
                cur = {k: v[:12] for k, v in inputs[sid].items()}
                changed = sorted(k for k in set(old) | set(cur) if old.get(k) != cur.get(k))
                upstream = sorted(
                    d
                    for d in self.pipeline.stages[sid].depends_on
                    if prev.get(d) is None or prev[d]["fingerprint"] != fps[d]
                )
                stale[sid] = {
                    "changed_inputs": changed,
                    "upstream_changed": upstream,
                    "definition_changed": not changed and not upstream,
                }
        return stale, reused, new

    def _approval_states(self, items: List[str]) -> Dict[str, Tuple[str, str]]:
        table = read_signoff(self.signoff_path)
        return {i: table.get(i, ("missing", f"{i} is not in the sign-off table")) for i in items}

    def _preflight(self) -> List[Tuple[str, str, str]]:
        out = []
        for gate in self.pipeline.policy.get("preflight", []):
            status, detail = check_gate(gate, self.root, self.env, self.locks)
            out.append((gate.get("name", gate["type"]), status, detail))
        return out

    def plan(self) -> dict:
        """Read-only: what would run, in which parallel waves, and why. Writes nothing."""
        fps, inputs = self._current()
        prev = self._previous()
        stale, _reused, new = self._replan_summary(fps, inputs, prev)
        selected = self._selected()
        waves = [[s for s in wave if s in selected] for wave in self.pipeline.waves()]
        stages = []
        for index, wave in enumerate(waves):
            for sid in wave:
                st = self.pipeline.stages[sid]
                state = "new" if sid in new else "stale" if sid in stale else "up-to-date"
                approvals = self._approval_states(st.approvals)
                stages.append(
                    {
                        "id": sid,
                        "wave": index,
                        "depends_on": st.depends_on,
                        "autonomy": st.autonomy,
                        "state": state,
                        "why": stale.get(sid),
                        "approvals": {k: v[0] for k, v in approvals.items()},
                        "release_gate": st.release_gate,
                    }
                )
        return {"waves": [w for w in waves if w], "stages": stages, "preflight": self._preflight()}

    # -- running ---------------------------------------------------------------------------------------------------

    def run(self) -> RunResult:
        run_id = self.clock().strftime("%Y%m%dT%H%M%S%f") + "Z-" + secrets.token_hex(2)
        self.ledger = Ledger(self.ledger_dir, run_id, self.clock)
        t0 = time.monotonic()
        self.ledger.append(
            "run_started",
            pipeline=self.pipeline.name,
            workers=self.workers,
            keep_going=self.keep_going,
            rollback_enabled=self.rollback,
            only=self.only,
        )
        self.log(f"run {run_id}")

        failed_pre = []
        for name, status, detail in self._preflight():
            self.ledger.append("preflight", gate=name, status=status, detail=detail)
            if status != "pass":
                failed_pre.append(name)
                self.log(f"  policy violation: {name}: {detail}")
        if failed_pre:
            self.ledger.append(
                "run_finished", status="ABORTED", reason="policy preflight failed", duration=round(time.monotonic() - t0, 3)
            )
            return RunResult(run_id, "ABORTED", {})

        self._fps, inputs = self._current()
        self._prev = self._previous()
        stale, reused, new = self._replan_summary(self._fps, inputs, self._prev)
        self.ledger.append("replan", stale=stale, reused=reused, new=new)
        if stale:
            self.log("  re-plan: " + ", ".join(f"{s} ({_why(w)})" for s, w in stale.items()))

        statuses = self._schedule(self._selected(), inputs)
        status = "ABORTED" if self._stopped else self._overall(statuses)
        self.ledger.append("run_finished", status=status, stages=statuses, duration=round(time.monotonic() - t0, 3))
        if status == "SUCCESS" and self.tag_green:
            tag = subprocess.run(
                ["git", "tag", "-f", "pipeline/last-green"], cwd=self.root, capture_output=True, text=True, check=False
            )
            self.ledger.append("tag_green", returncode=tag.returncode, detail=(tag.stdout + tag.stderr).strip()[-300:])
        self.log(f"result: {status}")
        return RunResult(run_id, status, statuses)

    @staticmethod
    def _overall(statuses: Dict[str, str]) -> str:
        values = set(statuses.values())
        if FAILED in values:
            return "FAILED"
        if BLOCKED in values or SKIPPED in values:
            return "BLOCKED"
        if AWAITING in values:
            return "AWAITING_APPROVAL"
        return "SUCCESS"

    def _stop_requested(self) -> bool:
        return (self.root / self.pipeline.policy.get("stop_file", "orchestration/STOP")).exists()

    def _schedule(self, selected: set, inputs) -> Dict[str, str]:
        order = [s for s in self.pipeline.topological_order() if s in selected]
        pending = list(order)
        running: Dict[cf.Future, str] = {}
        statuses: Dict[str, str] = {}
        halt: Optional[str] = None
        with cf.ThreadPoolExecutor(max_workers=self.workers) as pool:
            while pending or running:
                if halt is None and self._stop_requested():
                    halt = "stop file present"
                    self._stopped = True
                    self.ledger.append("safe_stop", reason=halt)
                progressed = False
                for sid in list(pending):
                    st = self.pipeline.stages[sid]
                    if not all(d in statuses for d in st.depends_on):
                        continue
                    bad = [d for d in st.depends_on if statuses[d] not in TECHNICAL_OK]
                    if bad:
                        self._skip(sid, statuses, f"upstream {bad[0]} is {statuses[bad[0]]}")
                    elif halt is not None:
                        self._skip(sid, statuses, f"safe-stop: {halt}")
                    elif len(running) < self.workers:
                        running[pool.submit(self._exec_stage, st, inputs[sid])] = sid
                    else:
                        continue
                    pending.remove(sid)
                    progressed = True
                if running:
                    done, _ = cf.wait(list(running), return_when=cf.FIRST_COMPLETED)
                    for fut in done:
                        sid = running.pop(fut)
                        statuses[sid] = fut.result().status
                        if statuses[sid] == FAILED and not self.keep_going and halt is None:
                            halt = f"stage {sid} failed"
                            self.ledger.append("safe_stop", reason=halt)
                elif not progressed:
                    for sid in list(pending):  # defensive: nothing can move
                        self._skip(sid, statuses, "unreachable")
                        pending.remove(sid)
        return statuses

    def _skip(self, sid: str, statuses: Dict[str, str], reason: str) -> None:
        statuses[sid] = SKIPPED
        self.ledger.append("stage_finished", stage=sid, status=SKIPPED, executed=False, gates_passed=False, detail=reason)
        self.log(f"  - {sid}: SKIPPED ({reason})")

    # -- one stage -------------------------------------------------------------------------------------------------

    def _exec_stage(self, stage: Stage, digests: Dict[str, str]) -> Outcome:
        fp = self._fps[stage.id]
        t0 = time.monotonic()
        self.ledger.append("stage_started", stage=stage.id, fingerprint=fp[:12], autonomy=stage.autonomy)
        self.log(f"  > {stage.id}")
        try:
            out = self._exec_stage_inner(stage, fp)
        except Exception as exc:  # a bug in the engine must never look like a pass
            out = Outcome(FAILED, f"internal error: {exc!r}")
        out.duration = time.monotonic() - t0
        self.ledger.append(
            "stage_finished",
            stage=stage.id,
            status=out.status,
            executed=out.executed,
            gates_passed=out.gates_passed,
            fallback=out.fallback,
            retried=out.retried,
            duration=round(out.duration, 3),
            fingerprint=fp,
            detail=out.detail[-600:],
            lineage={
                "depends_on": {d: self._fps[d][:12] for d in stage.depends_on},
                "inputs": {k: v[:12] for k, v in digests.items()},
                "decisions": stage.decisions,
                "ai_log": stage.ai_log,
                "autonomy": stage.autonomy,
                "approvals": out.approvals,
            },
        )
        mark = {PASSED: "ok", CACHED: "ok", DEGRADED: "degraded", AWAITING: "awaiting sign-off"}.get(out.status, out.status)
        self.log(f"  {'v' if out.status in TECHNICAL_OK else 'x'} {stage.id}: {out.status} ({mark}, {out.duration:.1f}s) {out.detail[:160]}")
        return out

    def _exec_stage_inner(self, stage: Stage, fp: str) -> Outcome:
        # Release gate: every human sign-off upstream must be granted before the action is allowed.
        if stage.release_gate:
            states = self._approval_states(self.pipeline.required_approvals(stage.id))
            rejected = [d for s, d in states.values() if s == "rejected"]
            if rejected:
                return Outcome(FAILED, "; ".join(rejected))
            missing = [item for item, (s, _d) in states.items() if s != "granted"]
            if missing:
                return Outcome(BLOCKED, "awaiting human sign-off: " + ", ".join(missing))

        # Entry gates: preconditions. A miss is BLOCKED (not a failure): nothing was attempted.
        for gate in stage.entry_gates:
            res = self._run_gate(stage, gate, "entry", retries=False)
            if res.status != "pass":
                return Outcome(BLOCKED, f"entry gate '{res.name}': {res.detail}")

        out = Outcome()
        prev = self._prev.get(stage.id)
        technical = PASSED
        if prev and prev["fingerprint"] == fp:
            technical, out.gates_passed = CACHED, True
        else:
            out.executed = True
            failure = self._run_exit_gates(stage, stage.exit_gates, "exit", out)
            if failure is None:
                out.gates_passed = True
            elif failure.status == "blocked":
                return Outcome(BLOCKED, f"exit gate '{failure.name}': {failure.detail}", executed=True, retried=out.retried)
            elif stage.fallback:
                self.ledger.append(
                    "fallback", stage=stage.id, reason=failure.name, overrides=stage.fallback.get("release_overrides", {})
                )
                env_extra = stage.fallback.get("release_overrides", {})
                if self._run_exit_gates(stage, stage.fallback["exit_gates"], "fallback", out, env_extra) is None:
                    technical, out.gates_passed, out.fallback = DEGRADED, True, True
                    out.detail = f"fallback: {stage.fallback.get('description', '')}"
            if not out.gates_passed:
                out.status = FAILED
                out.detail = f"exit gate '{failure.name}' failed: {failure.detail}"
                self._on_failure(stage)
                return out

        # Human approvals, evaluated on every run (never cached): sign-offs can be added or revoked at any time.
        if stage.approvals:
            states = self._approval_states(stage.approvals)
            out.approvals = {i: d for i, (_s, d) in states.items()}
            if any(s == "rejected" for s, _d in states.values()):
                out.status, out.detail = FAILED, "; ".join(d for s, d in states.values() if s == "rejected")
                return out
            if any(s != "granted" for s, _d in states.values()):
                out.status = AWAITING
                out.detail = "awaiting human sign-off: " + ", ".join(i for i, (s, _d) in states.items() if s != "granted")
                return out
        out.status = technical
        return out

    def _run_exit_gates(self, stage, gates, phase, out: Outcome, env_extra=None) -> Optional[GateResult]:
        """None = all passed; otherwise the first non-passing result."""
        for gate in gates:
            res = self._run_gate(stage, gate, phase, retries=True, env_extra=env_extra)
            if res.attempts > 1:
                out.retried = True
            if res.status != "pass":
                return res
        return None

    def _run_gate(self, stage: Stage, gate: dict, phase: str, *, retries: bool, env_extra=None) -> GateResult:
        name = gate.get("name", gate["type"])
        env = {**self.env, **(env_extra or {})}
        allowed = 0
        if retries and gate.get("transient"):
            allowed = min(int(gate.get("retries", 0)), int(self.pipeline.policy.get("max_retries", 2)))
        backoff = float(self.pipeline.policy.get("retry_backoff_seconds", 2.0))
        started = time.monotonic()
        attempt = 0
        while True:
            attempt += 1
            status, detail = check_gate(gate, self.root, env, self.locks)
            if status == "fail" and attempt <= allowed:
                self.ledger.append("gate_retry", stage=stage.id, gate=name, attempt=attempt, detail=detail[-300:])
                self.sleep(backoff * attempt)
                continue
            break
        res = GateResult(name, gate["type"], status, detail, attempt, time.monotonic() - started)
        self.ledger.append(
            "gate_result",
            stage=stage.id,
            phase=phase,
            gate=name,
            type=gate["type"],
            status=status,
            attempts=attempt,
            duration=round(res.duration, 3),
            detail=detail[-1200:],
        )
        return res

    def _on_failure(self, stage: Stage) -> None:
        """Safe-stop is the default. Rollback is only executed when the operator asked for it with --rollback."""
        if not stage.rollback:
            return
        argv = stage.rollback.get("argv")
        self.ledger.append(
            "rollback_recommended",
            stage=stage.id,
            description=stage.rollback.get("description", ""),
            argv=argv,
            will_execute=bool(self.rollback and argv),
        )
        if self.rollback and argv:
            proc = subprocess.run(argv, cwd=self.root, capture_output=True, text=True, check=False)
            self.ledger.append(
                "rollback_executed",
                stage=stage.id,
                argv=argv,
                returncode=proc.returncode,
                detail=(proc.stdout + proc.stderr).strip()[-400:],
            )


def _why(w: dict) -> str:
    if w.get("changed_inputs"):
        return "changed: " + ", ".join(w["changed_inputs"][:3]) + ("..." if len(w["changed_inputs"]) > 3 else "")
    if w.get("upstream_changed"):
        return "upstream: " + ", ".join(w["upstream_changed"])
    return "stage definition changed"
