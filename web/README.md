# Exchange control frontend

A Vue 3 + TypeScript SPA over the control plane's REST API. Read-only in this first slice: sign in,
then look at shards, securities, participants, releases, the live exchange status, and the audit of
who asked for what. Everything that *changes* something is still done over REST — see
[`../docs/ControlPlane.md`](../docs/ControlPlane.md).

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

## Three decisions worth knowing before changing anything

**One origin.** Vite proxies `/api` to `localhost:8080` rather than the SPA calling a second host.
That is not convenience: the session is a cookie and the CSRF defence is a cookie copied into a
header, and both are same-origin mechanisms. A second origin would mean CORS, credentialed
cross-origin requests and `SameSite=None` on the session cookie — three loosened settings to work
around a problem a proxy removes. Production should serve `dist/` from behind the same host.

**The router guard is a convenience, not the protection.** It exists so an operator sees a login
form instead of seven empty tables. What actually protects the data is the server answering 401; a
guard in a bundle the browser downloaded can always be edited by whoever downloaded it.

**Empty and failed never render the same way.** `AsyncTable` has three states on purpose. A control
plane that showed "no securities" when it meant "the request was refused" would be lying about the
state of the exchange, which is the one thing this UI exists not to do.

## Not wired into Gradle

Deliberately. `./gradlew build` stays npm-free and this stays a normal frontend project. Packaging
`dist/` into the control jar is a later decision, not a prerequisite for either half working.

## Layout

```
src/api/client.ts     fetch wrapper: session cookie, X-XSRF-TOKEN, typed ApiError
src/api/session.ts    the one piece of global state -- who is signed in
src/api/types.ts      hand-written mirrors of the Kotlin DTOs (the Kotlin is authoritative)
src/format.ts         fixed-point prices, 8 implied decimals, display only
src/router.ts         routes plus the guard
src/views/            one per section
```

`src/api/types.ts` is hand-written and the compiler cannot tell you when it drifts from the Kotlin.
Keep the field names identical to the DTOs rather than tidied.
