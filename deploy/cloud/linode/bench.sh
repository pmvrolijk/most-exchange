#!/usr/bin/env bash
# A throwaway Linode for measuring one shard with a physical core per spinning thread.
#
#   bench.sh up          create it (cloud-init.yaml), firewall to this IP's SSH only, wait for the
#                        second boot -- the one with isolcpus on the command line
#   bench.sh check       what the guest was given: topology, command line, isolated CPUs, the map
#   bench.sh probe       measure SMT pairing when the guest shows a flat topology
#   bench.sh sync        rsync this working tree (.git included, so a row names its commit) and
#                        build the five distributions the e2e scripts launch, on the box
#   bench.sh run '<cmd>' run a command in the repo on the box, inside tmux, logged to ~/runs/;
#                        survives a dropped connection -- rerun `bench.sh tail` to reattach
#   bench.sh tail [log]  follow a run's log until it finishes (default: the newest)
#   bench.sh fetch [dir] copy build/sweep*, build/attribution* and ~/runs back (default build/cloud/<date>)
#   bench.sh ssh [cmd]   a shell, or one command
#   bench.sh status      the Linode as the API sees it
#   bench.sh down [--yes] delete the Linode and its firewall. Billing stops here, not at `poweroff`.
#
# Knobs: LINODE_TYPE (g7-dedicated-64-32), LINODE_REGION (nl-ams), LINODE_IMAGE (linode/ubuntu24.04),
# SSH_KEY (~/.ssh/id_ed25519.pub), MY_IP (looked up if unset). State lives in build/cloud/linode.env.
#
# Why 64-32 and not 32-16: Linode's vCPUs are hardware threads, so 16 of them are most likely eight
# cores, and the engine is cache-bound -- a busy SMT sibling is the laptop's confound over again.
# 32 threads with the siblings offlined is 16 cores. `check` says whether that is what happened.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
STATE="$ROOT/build/cloud/linode.env"

TYPE="${LINODE_TYPE:-g7-dedicated-64-32}"
REGION="${LINODE_REGION:-nl-ams}"
IMAGE="${LINODE_IMAGE:-linode/ubuntu24.04}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519.pub}"
LABEL="most-bench"
REMOTE_REPO="most-exchange"

die() { echo "bench: $*" >&2; exit 1; }
load_state() { [ -f "$STATE" ] || die "no Linode recorded in $STATE -- run 'bench.sh up'"; . "$STATE"; }
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15 -o ConnectTimeout=10)
remote() { ssh "${SSH_OPTS[@]}" "most@$IP" "$@"; }

cmd_up() {
  command -v linode-cli > /dev/null || die "linode-cli is not installed"
  [ -r "$SSH_KEY" ] || die "no public key at $SSH_KEY"
  if [ -f "$STATE" ]; then
    . "$STATE"
    linode-cli linodes view "$LINODE_ID" > /dev/null 2>&1 && die "Linode $LINODE_ID ($IP) already exists -- 'bench.sh down' first"
  fi
  mkdir -p "$(dirname "$STATE")"
  local key my_ip user_data root_pass fw id cfg kernel
  key="$(cat "$SSH_KEY")"
  my_ip="${MY_IP:-$(curl -fsS https://api.ipify.org)}"
  [[ "$my_ip" =~ ^[0-9.]+$ ]] || die "could not work out this machine's IPv4 ($my_ip); set MY_IP"
  user_data="$(sed "s|@SSH_KEY@|$key|" "$HERE/cloud-init.yaml" | base64 | tr -d '\n')"
  # Never used -- SSH is key-only and root login is off -- but the API requires one.
  root_pass="$(openssl rand -base64 33)"

  echo "== firewall: SSH from $my_ip only"
  fw=$(linode-cli firewalls create --label "$LABEL-fw" \
    --rules.inbound_policy DROP --rules.outbound_policy ACCEPT \
    --rules.inbound "[{\"label\":\"ssh\",\"action\":\"ACCEPT\",\"protocol\":\"TCP\",\"ports\":\"22\",\"addresses\":{\"ipv4\":[\"$my_ip/32\"]}}]" \
    --json | jq -r '.[0].id')
  [ -n "$fw" ] && [ "$fw" != null ] || die "firewall was not created"

  echo "== creating $TYPE in $REGION ($IMAGE), not booted"
  id=$(linode-cli linodes create --type "$TYPE" --region "$REGION" --image "$IMAGE" --label "$LABEL" \
    --tags most-bench --root_pass "$root_pass" --authorized_keys "$key" --firewall_id "$fw" \
    --metadata.user_data "$user_data" --booted false --json | jq -r '.[0].id')
  [ -n "$id" ] && [ "$id" != null ] || { linode-cli firewalls delete "$fw" || true; die "Linode was not created"; }
  IP=$(linode-cli linodes view "$id" --json | jq -r '.[0].ipv4[0]')
  printf 'LINODE_ID=%s\nFIREWALL_ID=%s\nIP=%s\nTYPE=%s\nREGION=%s\n' "$id" "$fw" "$IP" "$TYPE" "$REGION" > "$STATE"
  echo "   Linode $id at $IP -- billing from now; 'bench.sh down' to stop it"

  # The GRUB drop-in only takes effect if the Linode boots the distribution kernel through GRUB,
  # not a Linode-supplied kernel. Set it before the first boot so the reboot lands on it.
  until linode-cli linodes view "$id" --json | jq -e '.[0].status == "offline"' > /dev/null; do sleep 5; done
  cfg=$(linode-cli linodes configs-list "$id" --json | jq -r '.[0].id')
  kernel=$(linode-cli linodes configs-list "$id" --json | jq -r '.[0].kernel')
  if [ "$kernel" != "linode/grub2" ]; then
    echo "== config $cfg boots $kernel; switching to linode/grub2"
    linode-cli linodes config-update "$id" "$cfg" --kernel linode/grub2 > /dev/null
  fi
  linode-cli linodes boot "$id" > /dev/null

  echo "== waiting for the second boot (cloud-init, then a reboot onto isolcpus) -- a few minutes"
  local deadline=$((SECONDS + 1200))
  until remote 'test -f /etc/most-cpus.env && grep -q isolcpus /proc/cmdline && systemctl is-active -q most-cpus' 2> /dev/null; do
    [ "$SECONDS" -lt "$deadline" ] || die "not ready after 20 minutes -- 'bench.sh ssh sudo cloud-init status --long'"
    sleep 15
  done
  echo "== ready"
  cmd_check
}

cmd_check() {
  load_state
  remote 'set -e
    echo "== lscpu";              lscpu | grep -E "^(Model name|CPU\(s\)|On-line|Off-line|Thread|Core|Socket|NUMA node\(s\)|L2|L3)"
    echo "== kernel command line"; cat /proc/cmdline
    echo "== isolated";           cat /sys/devices/system/cpu/isolated
    echo "== nohz_full";          cat /sys/devices/system/cpu/nohz_full 2>/dev/null || echo "(none)"
    echo "== /etc/most-cpus.env"; grep -v "^#" /etc/most-cpus.env
    echo "== load";               uptime
    echo "== java";               java -version 2>&1 | head -1
    echo "== tuned";              tuned-adm active'
  remote 'grep -q "^TOPOLOGY=flat-unverified" /etc/most-cpus.env' && cat >&2 <<'EOF'

WARNING: the guest shows one thread per core, so which vCPUs share a physical core is unknown and
nothing was offlined. Run 'bench.sh probe', write one CPU per core to /etc/most-cores on the box,
then 'sudo most-cpus --grub && sudo reboot'. Until then a pinned run is not "one core per thread".
EOF
  return 0
}

# Two spinning loops share a core's execution units only if they are SMT siblings: pin one to CPU 0
# and one to each other CPU in turn, and a sibling shows as a clear drop against running alone.
cmd_probe() {
  load_state
  remote 'python3 - <<"EOF"
import os, time, multiprocessing as mp
def spin(cpu, secs, out):
    os.sched_setaffinity(0, {cpu}); n = 0; end = time.perf_counter() + secs
    while time.perf_counter() < end: n += 1
    out.put(n)
def run(cpus, secs=0.6):
    q = mp.Queue(); ps = [mp.Process(target=spin, args=(c, secs, q)) for c in cpus]
    [p.start() for p in ps]; [p.join() for p in ps]; return [q.get() for _ in ps]
cpus = sorted(os.sched_getaffinity(0)); alone = run([0])[0]
print(f"cpu0 alone: {alone:,} iterations")
for c in cpus[1:]:
    r = min(run([0, c])) / alone
    print(f"cpu0 + cpu{c:<3} {r:5.2f}  {'<-- sibling?' if r < 0.8 else ''}")
EOF'
}

cmd_sync() {
  load_state
  echo "== rsync $ROOT -> $IP:$REMOTE_REPO"
  rsync -az --delete -e "ssh ${SSH_OPTS[*]}" \
    --exclude '/build/' --exclude '*/build/' --exclude '.gradle/' --exclude 'node_modules/' \
    --exclude '.idea/' --exclude '/web/dist/' \
    "$ROOT/" "most@$IP:$REMOTE_REPO/"
  echo "== ./gradlew installDist on the box"
  remote "cd $REMOTE_REPO && ./gradlew --console=plain -q :engine:installDist :gateway:installDist :market-data:installDist :discovery:installDist :tools:installDist && git rev-parse --short HEAD && git status --short | wc -l | xargs echo 'uncommitted:'"
}

cmd_run() {
  load_state
  [ $# -ge 1 ] || die "usage: bench.sh run '<command, run from the repo root>'"
  local ts; ts="$(date +%Y%m%d-%H%M%S)"
  # The command travels as a file, so whatever quoting it has reaches bash on the box untouched.
  printf '%s\n' "$*" | remote "mkdir -p ~/runs && cat > ~/runs/$ts.sh"
  remote "cat > ~/runs/$ts.wrap" <<WRAP
cd ~/$REMOTE_REPO
{ echo "\$ \$(cat ~/runs/$ts.sh)"; echo "# \$(date -Is) load \$(cut -d' ' -f1-3 /proc/loadavg)"
  bash ~/runs/$ts.sh; echo "== exit \$?"; } > ~/runs/$ts.log 2>&1
WRAP
  remote "tmux new-session -d -s run-$ts 'bash ~/runs/$ts.wrap'"
  echo "== started run-$ts on $IP (log ~/runs/$ts.log)"
  cmd_tail "$ts.log"
}

cmd_tail() {
  load_state
  local log="${1:-}"
  [ -n "$log" ] || log="$(remote 'cd ~/runs 2>/dev/null && ls -t -- *.log 2>/dev/null | head -1')"
  [ -n "$log" ] || die "no runs on the box yet"
  local session="run-${log%.log}"
  remote "tail -n +1 -F ~/runs/$log & t=\$!; while tmux has-session -t $session 2>/dev/null; do sleep 2; done; sleep 1; kill \$t"
}

cmd_fetch() {
  load_state
  local dest="${1:-$ROOT/build/cloud/$(date +%Y%m%d)}"
  mkdir -p "$dest"
  rsync -az -e "ssh ${SSH_OPTS[*]}" --exclude 'aeron/' --exclude 'cluster-host/' \
    "most@$IP:$REMOTE_REPO/build/sweep*" "most@$IP:$REMOTE_REPO/build/attribution*" "$dest/" 2>/dev/null || true
  rsync -az -e "ssh ${SSH_OPTS[*]}" "most@$IP:runs" "$dest/"
  echo "== fetched into $dest"
}

cmd_ssh() { load_state; ssh -t "${SSH_OPTS[@]}" "most@$IP" "$@"; }

cmd_status() {
  load_state
  linode-cli linodes view "$LINODE_ID" --text --format id,label,type,region,status,ipv4
}

cmd_down() {
  load_state
  if [ "${1:-}" != --yes ]; then
    read -r -p "Delete Linode $LINODE_ID ($IP) and firewall $FIREWALL_ID? Fetch first if you need the runs. [y/N] " a
    [ "$a" = y ] || die "left running"
  fi
  linode-cli linodes delete "$LINODE_ID"
  linode-cli firewalls delete "$FIREWALL_ID" || echo "bench: firewall $FIREWALL_ID not deleted -- remove it by hand" >&2
  rm -f "$STATE"
  echo "== deleted; billing stopped"
}

case "${1:-}" in
  up|check|probe|sync|run|tail|fetch|ssh|status|down) c="$1"; shift; "cmd_$c" "$@" ;;
  *) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
