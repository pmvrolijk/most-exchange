# Exchange control frontend

A Vue 3 + TypeScript SPA over the control plane's REST API. Sign in, then read and edit the draft
topology (shards, securities, participants), publish releases, author the trading calendar, manage
operator accounts, watch the live exchange, and drive the four commands that move a market. The API
is the authority for all of it — see [`../docs/ControlPlane.md`](../docs/ControlPlane.md).

```sh
npm install
npm run dev        # http://localhost:5173, proxying /api to the control plane on :8080
npm run build      # typecheck (vue-tsc) then bundle into dist/
```

The control plane must be running, with an operator seeded:

```sh
docker start most-control-db
CONTROL_ADMIN_PASSWORD=... ./control/build/install/control/bin/control
```

## Four decisions worth knowing before changing anything

**One origin.** Vite proxies `/api` to `localhost:8080` rather than the SPA calling a second host.
That is not convenience: the session is a cookie and the CSRF defence is a cookie copied into a
header, and both are same-origin mechanisms. A second origin would mean CORS, credentialed
cross-origin requests and `SameSite=None` on the session cookie — three loosened settings to work
around a problem a proxy removes. Production should serve `dist/` from behind the same host.

**Consequence is visible before the click, not after it.** The four market-moving commands —
definition, session, purge, reopen — each confirm with the specific thing they will do to a live
exchange (a session transition is shard-wide; going to `CONTINUOUS` from anywhere but
`OPEN_AUCTION` skips the auction; a reopen re-seeds before `PRE_OPEN` because `staticReference` is
only reset by an executing uncross). A generic "are you sure?" would train an operator to click
through the one that mattered. Their outcomes stay on screen rather than appearing as a toast:
operator commands are unacknowledged, so `sent` without `confirmed` is a real state someone has to
reason about.

**The router guard is a convenience, not the protection.** It exists so an operator sees a login
form instead of seven empty tables. What actually protects the data is the server answering 401; a
guard in a bundle the browser downloaded can always be edited by whoever downloaded it.

**A 64-bit identity is a string, in both languages.** `universeVersion` is a hash, and JSON numbers
are IEEE 754 doubles in every browser: parsed as a number, `9181280125937456696` reads back as
`9181280125937457000`. The control plane serializes it with `ToStringSerializer` and `types.ts`
types it as `string`, so what the console displays is what the exchange produced. Nothing in Kotlin
would ever notice this, which is why the test that guards it asserts on the JSON text.

**Waiting for a book and having an empty book never render the same way.** The Books screen draws a
ladder only when the control plane reports `synchronised`. Otherwise it says so and draws nothing:
depth that is stale or partial looks exactly like depth that is current, and a console that showed
the last good ladder after a gap would be lying in the one place it matters most. This is the same
rule as the one below, applied to a live feed instead of a request.

**Empty and failed never render the same way.** `AsyncTable` has three states on purpose. A control
plane that showed "no securities" when it meant "the request was refused" would be lying about the
state of the exchange, which is the one thing this UI exists not to do.

## The screens

| | |
| --- | --- |
| Status | The exchange as the L3 feed reports it, plus the draft topology's readiness to publish. |
| Operations | Definition, session, purge, reopen. The only screen that touches a running market. |
| Books | Live depth per security, streamed over SSE. A book that is not synchronised shows no ladder — see below. |
| Shards / Securities / Participants | The draft topology. Nothing here reaches a node until a release is published and the processes restart. |
| Releases | Publish an immutable numbered release, import an existing shard security file, read the exact bytes of any artifact. |
| Schedules | The trading calendar, holidays, which shard runs which schedule, and what the reconciler did or refused to do. |
| Audit | Who asked for every market-moving command. Sent, not applied. |
| Operators | Accounts. One role, full access — the audit is what distinguishes who did what. |

## Not wired into Gradle

Deliberately. `./gradlew build` stays npm-free and this stays a normal frontend project. Packaging
`dist/` into the control jar is a later decision, not a prerequisite for either half working.

## Layout

```
src/api/client.ts     fetch wrapper: session cookie, X-XSRF-TOKEN, typed ApiError, text bodies
src/api/books.ts      the live book feed: EventSource, with a polling fallback
src/api/collection.ts useCollection / useResource / useMutation -- loading, error, reload
src/api/session.ts    the one piece of global state -- who is signed in
src/api/types.ts      hand-written mirrors of the Kotlin DTOs (the Kotlin is authoritative)
src/components/       DataState (the three states), Modal, ConfirmDialog, AsyncTable
src/format.ts         fixed-point prices, 8 implied decimals, parsed by string not by float
src/router.ts         routes plus the guard
src/views/            one per section; OperationsView is the market-moving one
```

`src/api/types.ts` is hand-written and the compiler cannot tell you when it drifts from the Kotlin.
Keep the field names identical to the DTOs rather than tidied.
