#!/usr/bin/env bash
# Guard: the auto-derived live-seat budget reflects BOTH memory and cores.
#
# Memory alone was the wrong input. A permit buys a render daemon and a render is CPU-bound, so a
# RAM-rich, core-poor box derived a budget it could not work — and the old [2, 8] clamp meant a large
# box stopped scaling at all. Measured on preview.coo.ee (48 GiB, 8 cores): memory afforded 40, the
# clamp allowed 8, and the box ran a fifth of what its cores could drive.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

# Pull just the constants and the function out, so this exercises the real arithmetic without
# running an entrypoint that expects to be inside the container.
eval "$(sed -n '/^SEATS_PER_CPU=/,/^}$/p' "${entrypoint}")"
eval "$(sed -n '/^effective_cpus() {$/,/^}$/p' "${entrypoint}")"
for fn in derive_live_seats effective_cpus; do
  declare -F "${fn}" >/dev/null || {
    echo "FAIL: ${fn} not found in ${entrypoint} — the extractor is broken." >&2
    exit 1
  }
done

check() { # eff_mb cpus expected why
  local got; got="$(derive_live_seats "$1" "$2")"
  [[ "${got}" == "$3" ]] || {
    echo "FAIL: ${4} — ${1}MB/${2}cpu gave ${got}, expected ${3}" >&2
    exit 1
  }
  echo "PASS: ${4} (${1}MB, ${2} cpu -> ${got})"
}

# The deployed box. Memory affords 40, cores afford 16; the cores govern.
check 49152 8 16 "a large box is bounded by its cores, not clamped at 8"

# The old ceiling was 8. Prove we are past it — this is the regression that matters.
[[ "$(derive_live_seats 49152 8)" -gt 8 ]] || {
  echo "FAIL: the old 8-seat clamp is still in force" >&2
  exit 1
}
echo "PASS: the old 8-seat clamp is gone"

# Core-poor, RAM-rich: memory would have said 40, the cores say 4.
check 49152 2 4 "cores bound a RAM-rich box"

# RAM-poor, core-rich: the reverse — memory governs.
check 4096 32 2 "memory bounds a core-rich box"

# The reference 4 GB box still gets its floor.
check 4096 4 2 "the 4 GB reference box keeps the floor of 2"

# 8 GB / 4 cores: memory governs. 4 rather than the 5 this derived before the headroom reserve —
# 5 seats implied an 84% budget, past the optimizer's resume threshold.
check 8192 4 4 "an 8 GB box is bounded by memory, with headroom left"

# A very large box is still bounded, so a runaway derivation cannot spawn daemons without limit.
check 262144 128 32 "a huge box is capped at the ceiling"

# Unknown core count must not derive zero. Falling back to memory keeps the old behaviour, which is
# the right answer when half the inputs are missing.
check 49152 0 29 "an unknown core count falls back to the memory figure"
check 8192 0 4 "an unknown core count on a small box falls back to memory"

# Unknown memory must not underflow into a negative seat count.
check 0 8 2 "unknown memory falls back to the floor, not a negative"

# ---------------------------------------------------------------------------------------------
# The headroom invariant: a derived budget must leave the theme optimizer room to RESUME.
# ---------------------------------------------------------------------------------------------
#
# This is the regression that matters most, because its symptom is silence. The old derivation was
# `(eff_mb - 1024) / 1200`, so the implied budget landed within one seat of the entire cgroup —
# ~93-96% utilisation by construction. The optimizer refuses to resume below 25% free
# (`resumeMemoryAvailableFraction`), so any box where memory was the binding side sized itself into
# permanent suspension: renders still succeeded, `status` still read ok, and background theme
# optimisation simply never progressed. Measured on preview.coo.ee at ~11 GiB / 8 cores: 8 seats,
# 15.5% available, 153 suspensions to 48 resumes.
#
# So assert the property rather than the numbers: projected utilisation stays within budget for
# every box whose derivation is not floored.
budget_pct() { # eff_mb cpus -> percent of eff_mb the derived seat budget implies
  local eff="$1" seats
  seats="$(derive_live_seats "$eff" "$2")"
  echo $(( (SEATS_MEM_RESERVE_MB + seats * SEATS_MEM_PER_SEAT_MB) * 100 / eff ))
}

max_pct=$(( 100 - SEATS_MEM_HEADROOM_PCT ))
for box in "8192 4" "11264 8" "16384 8" "49152 8" "49152 16" "49152 0" "131072 64" "262144 128"; do
  # shellcheck disable=SC2086
  pct="$(budget_pct ${box})"
  (( pct <= max_pct )) || {
    echo "FAIL: ${box% *}MB/${box#* }cpu budgets ${pct}% of memory, over the ${max_pct}% ceiling" >&2
    echo "      That sizes the box past the optimizer's resume threshold — the exact wedge this reserve exists to prevent." >&2
    exit 1
  }
  echo "PASS: ${box% *}MB/${box#* }cpu budgets ${pct}% (<= ${max_pct}%)"
done

# The one deliberate exception. A 4 GB reference box floors at 2 seats and DOES exceed the headroom:
# a box that cannot run two cheap CMP sessions concurrently is worse than one whose optimizer
# throttles. Asserted so the exception stays a decision rather than becoming a surprise.
[[ "$(derive_live_seats 4096 4)" == "2" ]] || {
  echo "FAIL: the 4 GB box no longer floors at 2" >&2
  exit 1
}
(( $(budget_pct 4096 4) > max_pct )) || {
  echo "FAIL: the 4 GB floor no longer exceeds the headroom — if that is now intentional, delete this check." >&2
  exit 1
}
echo "PASS: the 4 GB floor still wins over the headroom reserve (a documented exception)"

echo "PASS: all derive_live_seats checks"

# ---------------------------------------------------------------------------------------------
# effective_cpus: the quota, not just the affinity mask.
# ---------------------------------------------------------------------------------------------
#
# `nproc` reports the processors VISIBLE to this process. A container constrained with
# `docker --cpus 2` whose cpuset was not also narrowed sees the host's cores, so a 16-core host
# derived up to the 32-seat ceiling for a container entitled to two CPUs' worth of work — the same
# class of mistake as sizing from memory alone.

cpu_tmp="$(mktemp -d)"
trap 'rm -rf "${cpu_tmp}"' EXIT

cpucheck() { # expected visible why
  local got; got="$(effective_cpus "$2")"
  [[ "${got}" == "$1" ]] || {
    echo "FAIL: ${3} — visible ${2} gave ${got}, expected ${1}" >&2
    exit 1
  }
  echo "PASS: ${3} (visible ${2} -> ${got})"
}

# cgroup v2, the case from the review: cpu.max "200000 100000" is 2 CPUs, with 3 visible.
printf '200000 100000\n' > "${cpu_tmp}/cpu.max"
CPU_MAX_FILE="${cpu_tmp}/cpu.max" cpucheck 2 3 "a v2 quota bounds the visible count"
CPU_MAX_FILE="${cpu_tmp}/cpu.max" cpucheck 2 16 "and bounds a big host just as hard"

# A quota LOOSER than the affinity mask must not inflate the answer.
printf '3200000 100000\n' > "${cpu_tmp}/cpu.wide"
CPU_MAX_FILE="${cpu_tmp}/cpu.wide" cpucheck 4 4 "a quota wider than the cores changes nothing"

# `max` is unlimited, not a number — the visible count stands.
printf 'max 100000\n' > "${cpu_tmp}/cpu.unlimited"
CPU_MAX_FILE="${cpu_tmp}/cpu.unlimited" cpucheck 8 8 "an unlimited v2 quota leaves nproc alone"

# Sub-single-CPU quota floors at 1 rather than reading as unknown and being ignored.
printf '50000 100000\n' > "${cpu_tmp}/cpu.half"
CPU_MAX_FILE="${cpu_tmp}/cpu.half" cpucheck 1 8 "half a CPU floors at 1, it does not vanish"

# cgroup v1 splits it across two files, with -1 for unlimited.
printf '200000\n' > "${cpu_tmp}/quota"
printf '100000\n' > "${cpu_tmp}/period"
CPU_MAX_FILE="${cpu_tmp}/absent" CPU_QUOTA_FILE="${cpu_tmp}/quota" CPU_PERIOD_FILE="${cpu_tmp}/period" \
  cpucheck 2 6 "a v1 quota is read too"
printf -- '-1\n' > "${cpu_tmp}/quota-unlimited"
CPU_MAX_FILE="${cpu_tmp}/absent" CPU_QUOTA_FILE="${cpu_tmp}/quota-unlimited" CPU_PERIOD_FILE="${cpu_tmp}/period" \
  cpucheck 6 6 "an unlimited v1 quota leaves nproc alone"

# No cgroup files at all — a developer laptop, and the pre-existing behaviour.
CPU_MAX_FILE="${cpu_tmp}/absent" CPU_QUOTA_FILE="${cpu_tmp}/absent" CPU_PERIOD_FILE="${cpu_tmp}/absent" \
  cpucheck 4 4 "no cgroup files leaves the visible count untouched"

# A garbled file is missing input, not permission to invent a number.
printf 'not-a-quota\n' > "${cpu_tmp}/cpu.garbage"
CPU_MAX_FILE="${cpu_tmp}/cpu.garbage" cpucheck 4 4 "an unparseable quota is ignored"

# End to end: the quota is what reaches the seat budget. 16 visible cores on a RAM-rich box derive
# 29 (memory governs, after the headroom reserve); a 2-CPU quota derives 4.
seats_visible="$(derive_live_seats 49152 16)"
seats_quota="$(derive_live_seats 49152 "$(CPU_MAX_FILE="${cpu_tmp}/cpu.max" effective_cpus 16)")"
[[ "${seats_visible}" == "29" ]] || {
  echo "FAIL: 16 visible cores on a 48 GB box should derive 29, got ${seats_visible}" >&2
  exit 1
}
[[ "${seats_quota}" == "4" ]] || {
  echo "FAIL: a 2-CPU quota should derive 4 seats, got ${seats_quota}" >&2
  exit 1
}
echo "PASS: the quota reaches the seat budget (29 -> 4 on a 2-CPU quota)"

echo "PASS: all effective-cpu checks"
