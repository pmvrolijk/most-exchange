# Thread pinning for run-sweep.sh and run-attribution.sh -- sourced, never run.
#
# With PIN unset this defines two empty prefixes and a no-op, so a script that sources it launches
# every process exactly as it did before the knob existed. With PIN=<cpus.env> (written at boot by
# deploy/cloud/linode/cloud-init.yaml's most-cpus, one CPU per physical core) it:
#
#   - launches every process under `taskset -c $HOUSEKEEPING`, so GC, JIT, the Aeron client
#     conductors and anything else nobody named stay on the housekeeping cores;
#   - then moves each named, spinning agent thread to a core of its own (pin_threads);
#   - launches `most load` under `taskset -c $LOADER_CPUS`.
#
# Two things about isolcpus decide that shape. An isolated CPU is outside the scheduler's load
# balancing, so a mask of several isolated CPUs puts every thread in it on the first one: each agent
# therefore gets exactly one isolated CPU, never a set. And the loaders, which are two threads each
# and started once per rate, get CPUs that are *not* isolated, where the scheduler can spread them.
#
# Thread names are the ones the runtime gives the kernel -- a Java thread's name cut to 15 characters
# -- and the Aeron agent role names behind them. HotSpot keeps the *first* 15 characters and a
# native image keeps the *last* 15 (market-data-poller is market-data-pol on the JVM and
# ket-data-poller native), so both spellings are matched. The service container's thread takes the
# container's serviceName, which EngineMain sets to matching-engine. A native image's main thread
# carries the image name -- matching-engine again -- so a process's main thread (tid = pid) is never
# pinned. `pin_threads --list <pid>...` prints what a process actually has, which is the first thing
# to run on a new Aeron version or a new runtime.

ON_HOUSE=""
ON_LOAD=""

if [ -z "${PIN:-}" ]; then
  pin_threads() { :; }
  return 0 2>/dev/null || exit 0
fi

[ -r "$PIN" ] || { echo "PIN=$PIN: not readable" >&2; exit 1; }
# shellcheck disable=SC1090
. "$PIN"
command -v taskset > /dev/null || { echo "PIN is set but taskset is not installed" >&2; exit 1; }
ON_HOUSE="taskset -c $HOUSEKEEPING"
ON_LOAD="taskset -c $LOADER_CPUS"
echo "== pinning from $PIN (topology $TOPOLOGY): housekeeping $HOUSEKEEPING, loaders $LOADER_CPUS"

# The CPU for a thread name, or nothing for a thread that stays on housekeeping. Gateways take
# CPU_GATEWAYS in the order they are met, which is the order they were started in.
_pin_cpu_for() { # _pin_cpu_for <comm>
  case "$1" in
    driver-conducto*|"[driver-conduct"*) echo "$CPU_DRIVER_CONDUCTOR" ;;  # DEDICATED, or SHARED's composite
    sender)                              echo "$CPU_SENDER" ;;
    receiver)                            echo "$CPU_RECEIVER" ;;
    "[sender, receiv"*)                  echo "$CPU_SENDER" ;;            # SHARED_NETWORK
    archive-conduct*|"[archive-condu"*)  echo "$CPU_ARCHIVE" ;;
    archive-recorde*)                    echo "$CPU_ARCHIVE_RECORDER" ;;  # ArchiveThreadingMode.DEDICATED
    consensus-modul*)                    echo "$CPU_CONSENSUS" ;;
    matching-engine|clustered-servi*)    echo "$CPU_SERVICE" ;;  # EngineMain's serviceName, or Aeron's default
    gateway-poller)                      _pin_next_gateway ;;
    market-data-pol*|ket-data-poller)    echo "$CPU_MARKETDATA" ;;  # JVM, native
    *)                                   ;;
  esac
}
_PIN_GW_USED=0
_pin_next_gateway() {
  # shellcheck disable=SC2086
  set -- $CPU_GATEWAYS
  shift "$_PIN_GW_USED" 2>/dev/null || { echo "" ; return; }
  echo "${1:-}"
}

# pin_threads <pid>...        move every named agent thread to its core; fail if a core is missing
# pin_threads --list <pid>... print each thread's name and the CPU it may run on, change nothing
pin_threads() {
  local list=0 pid tid comm cpu applied=0 roles=""
  [ "${1:-}" = "--list" ] && { list=1; shift; }
  _PIN_GW_USED=0
  for pid in "$@"; do
    [ -d "/proc/$pid/task" ] || continue
    for tid in $(ls "/proc/$pid/task"); do
      comm=$(cat "/proc/$pid/task/$tid/comm" 2>/dev/null) || continue
      if [ "$list" -eq 1 ]; then
        printf '   %7s %7s  %-16s %s\n' "$pid" "$tid" "$comm" "$(taskset -pc "$tid" 2>/dev/null | sed 's/.*: //')"
        continue
      fi
      [ "$tid" = "$pid" ] && continue  # the main thread: blocked on the shutdown barrier, and named after the image
      cpu=$(_pin_cpu_for "$comm")
      [ -n "$cpu" ] || { case "$comm" in gateway-poller) echo "pin: no CPU left in CPU_GATEWAYS for another gateway" >&2; return 1 ;; esac; continue; }
      [ "$comm" = "gateway-poller" ] && _PIN_GW_USED=$((_PIN_GW_USED + 1))
      taskset -pc "$cpu" "$tid" > /dev/null || { echo "pin: taskset $cpu $tid ($comm) failed" >&2; return 1; }
      printf '   pinned %-16s (pid %s, tid %s) -> cpu %s\n' "$comm" "$pid" "$tid" "$cpu"
      applied=$((applied + 1)); roles="$roles $comm"
    done
  done
  [ "$list" -eq 1 ] && return 0
  # The threads the order path cannot do without. A missing one means a renamed thread, and a run
  # with it unpinned would be labelled pinned -- so refuse rather than measure.
  local want
  for want in consensus-modul gateway-poller; do
    case "$roles" in *" $want"*) ;; *) echo "pin: no thread named $want* found -- run pin_threads --list" >&2; return 1 ;; esac
  done
  case "$roles" in *" driver-conducto"*|*" [driver-conduct"*) ;; *) echo "pin: no media driver thread found" >&2; return 1 ;; esac
  case "$roles" in *" market-data-pol"*|*" ket-data-poller"*) ;; *) echo "pin: no market-data poller thread found" >&2; return 1 ;; esac
  case "$roles" in *" matching-engine"*|*" clustered-servi"*) ;; *) echo "pin: no service container thread (matching-engine / clustered-service*) found" >&2; return 1 ;; esac
  echo "   $applied threads pinned"
}
