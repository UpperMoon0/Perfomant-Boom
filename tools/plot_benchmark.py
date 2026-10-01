"""Capture passing real-game evidence and plot the versioned README chart.

python tools/plot_benchmark.py --capture PATH_TO_RUN [PATH_TO_ANOTHER_RUN]
python tools/plot_benchmark.py  # regenerate from the checked-in snapshot
Requires matplotlib. Synthetic/JUnit-only timings are not accepted.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DATA = None


def capture(directories):
    sys.path.insert(0, str(ROOT / "tools"))
    from live_boom_test import summarize, verify_lifecycle_evidence
    version = re.search(r"(?m)^mod_version\s*=\s*(\S+)",
                        (ROOT / "gradle.properties").read_text(encoding="utf-8"))[1]
    runs = []
    for directory in directories:
        result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
        if result.get("profiling_enabled"):
            raise ValueError("Profiled runs are diagnostic; rerun without profiling for the chart")
        gates = ("pass", "gameplay_pass", "clean_shutdown", "persistence_pass",
                 "lifecycle_pass", "source_unchanged")
        if not all(result.get(gate) is True for gate in gates) or result.get("forced_cleanup") is not False:
            raise ValueError(f"Run failed its real-game verification gates: {directory}")
        verify_lifecycle_evidence(directory, result["run_id"])
        summary = summarize(directory)
        if summary != result["summary"]:
            raise ValueError("Saved summary disagrees with raw samples")
        comparable = [row for row in summary if row["scenario"] == "no-drops"]
        if len(comparable) != 2 or any(row["samples"] != 5 for row in comparable):
            raise ValueError("Expected five no-drops pairs")
        raw = json.loads((directory / "server-samples.json").read_text(encoding="utf-8"))
        for sample in raw:
            count = len(sample["observedServerTickMs"])
            for series, total in (("observedServerThreadCpuMs", "totalObservedServerThreadCpuMs"),
                                  ("observedServerThreadAllocatedKiB", "totalObservedServerThreadAllocatedKiB")):
                values = sample[series]
                if (len(values) != count or any(not math.isfinite(value) or value < 0 for value in values)
                        or not math.isclose(sum(values), sample[total], rel_tol=1e-9, abs_tol=1e-6)):
                    raise ValueError(f"Invalid per-tick resource evidence: {series}")
        clients = [json.loads((directory / f"client-sample-{sample['trial']['index']:02d}.json")
                              .read_text(encoding="utf-8")) for sample in raw]
        inputs = json.loads((directory / "frozen-inputs.json").read_text(encoding="utf-8"))
        # Record hashes without publishing absolute launch paths or environment values.
        source_hashes = {key: value for key, value in inputs.items()
                         if key.startswith("common/src/") or key in ("tools/live_boom_test.py", "gradle.properties")}
        for key, digest in source_hashes.items():
            if hashlib.sha256((ROOT / key).read_bytes()).hexdigest() != digest:
                raise ValueError(f"Current benchmark inputs differ from this run: {key}")
        environment = json.loads((directory / "resource-environment.json").read_text(encoding="utf-8"))
        runs.append(dict(result=result, environment=environment, source_hashes=source_hashes,
                         server_samples=raw, client_samples=clients))
    if len({run["result"]["loader"] for run in runs}) != len(runs):
        raise ValueError("Capture one run per loader")
    if DATA.exists() and json.loads(DATA.read_text(encoding="utf-8"))["mod_version"] != version:
        raise ValueError("Choose a separate data file for this mod version; historical snapshots are preserved")
    DATA.parent.mkdir(parents=True, exist_ok=True)
    snapshot = dict(mod_version=version, minecraft="1.20.1", scenario="no-drops", power=10,
                    warmup_pairs=3, measured_pairs=5,
                    machine=dict(cpu="Intel Core i3-8100 @ 3.60 GHz", ram_gib=15.93, os="Windows 10 Home"),
                    scope="Real dedicated server plus real graphical client; measured source hashes recorded; checkout state in each result",
                    runs=runs)
    DATA.write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, nargs="+")
    parser.add_argument("--data", type=Path, help="Snapshot path; default uses the configured mod version")
    args = parser.parse_args()
    global DATA
    configured = re.search(r"(?m)^mod_version\s*=\s*(\S+)", (ROOT / "gradle.properties").read_text(encoding="utf-8"))[1]
    DATA = args.data or ROOT / f"docs/benchmarks/live-v{configured}-1.20.1.json"
    if args.capture:
        capture(args.capture)
    snapshot = json.loads(DATA.read_text(encoding="utf-8"))
    version = snapshot["mod_version"]
    runs = sorted(snapshot["runs"], key=lambda run: run["result"]["loader"])
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11,
                         "svg.hashsalt": "perfomant-boom-live-benchmark"})
    fig, axes = plt.subplots(2, 2, figsize=(13, 8.5), facecolor="#f8fafc")
    metrics = (("activeWorkMs", "Active explosion work", "Milliseconds", 1),
               ("maxObservedServerTickMs", "Heaviest observed server tick", "Milliseconds", 1),
               ("totalObservedServerThreadCpuMs", "Server-thread CPU work", "CPU milliseconds", 1),
               ("totalObservedServerThreadAllocatedKiB", "Server-thread memory allocated", "MiB allocated", 1024))
    y = np.arange(len(runs))
    for ax, (metric, title, unit, divisor) in zip(axes.flat, metrics):
        ax.set_facecolor("#f8fafc")
        rows = [{row["engine"]: row for row in run["result"]["summary"]
                 if row["scenario"] == "no-drops"} for run in runs]
        largest = max(row[engine][metric]["q3"] / divisor for row in rows for engine in ("vanilla", "fast"))
        for engine, offset, label, color in zip(("vanilla", "fast"), (-.18, .18),
                                              ("Vanilla", f"Perfomant Boom v{version}"), ("#64748b", "#0891b2")):
            values = [row[engine][metric]["median"] / divisor for row in rows]
            lower = [v - row[engine][metric]["q1"] / divisor for v, row in zip(values, rows)]
            upper = [row[engine][metric]["q3"] / divisor - v for v, row in zip(values, rows)]
            ax.barh(y + offset, values, height=.29, color=color, label=label,
                    xerr=np.array([lower, upper]), capsize=4, error_kw=dict(ecolor="#0f172a", lw=1))
            for yy, value, high in zip(y + offset, values, upper):
                ax.text(value + high + largest * .025, yy, f"{value:.2f}", va="center", fontsize=11)
        ax.set_yticks(y, [run["result"]["loader"].capitalize() for run in runs])
        ax.invert_yaxis()
        ax.set_xlim(0, largest * 1.35)
        ax.set_xlabel(unit + " - lower is better", fontsize=10)
        ax.set_title(title, fontweight="bold", pad=12)
        ax.grid(axis="x", color="#e2e8f0")
        ax.set_axisbelow(True)
        ax.tick_params(axis="y", length=0)
        for spine in ax.spines.values():
            spine.set_visible(False)
    fig.suptitle(f"Perfomant Boom v{version} vs vanilla: real-game benchmark", x=.035, ha="left",
                 fontsize=20, fontweight="bold", color="#0f172a")
    fig.text(.035, .915, "Minecraft 1.20.1 | Power 10, no drops | 3 warmup + 5 measured pairs | Median with quartile whiskers",
             fontsize=11, color="#475569")
    handles, labels = axes[0, 0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(.5, .90), ncol=2, frameon=False)
    fig.text(.035, .035, "Real dedicated server + graphical client; exact crater, block/light, clean shutdown and reload checks passed.\n"
             "CPU and allocations cover observed server ticks + aftermath; allocations are not peak RAM or retained memory.\n"
             "CPU counter is coarse on this host (15.625 ms increments) | Windows / Core i3-8100 / ~16 GB RAM | 30 Sep 2026",
             fontsize=10, color="#475569")
    fig.subplots_adjust(left=.10, right=.97, top=.79, bottom=.18, wspace=.25, hspace=.55)
    target = ROOT / f"docs/images/benchmark-live-v{version}"
    target.parent.mkdir(parents=True, exist_ok=True)
    svg = Path(str(target) + ".svg")
    fig.savefig(svg, metadata={"Date": None})
    svg.write_text("\n".join(line.rstrip() for line in svg.read_text(encoding="utf-8").splitlines()) + "\n",
                   encoding="utf-8")
    fig.savefig(Path(str(target) + ".png"), dpi=160)
    plt.close(fig)


if __name__ == "__main__":
    main()
